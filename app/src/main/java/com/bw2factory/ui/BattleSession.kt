package com.bw2factory.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bw2factory.data.GameData
import com.bw2factory.engine.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A float animated with plain delays. The battle runs in viewModelScope, which has no MonotonicFrameClock,
 * so Compose's Animatable.animateTo would throw there (that was the crash on battle start).
 */
class Anim(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set

    fun snapTo(v: Float) { value = v }

    suspend fun animateTo(target: Float, durationMs: Int) {
        val start = value
        val steps = maxOf(1, durationMs / FRAME_MS)
        for (i in 1..steps) {
            delay(FRAME_MS.toLong())
            value = start + (target - start) * i / steps
        }
        value = target
    }

    private companion object { const val FRAME_MS = 16 }
}

enum class InputMode { NONE, COMMAND, FIGHT, PARTY, REPLACE, INFO }

/** A single move's visual effect, drawn procedurally from its type and category. */
class MoveEffect(val from: Int, val to: Int, val type: Int, val category: String) {
    val anim = Anim(0f)
}

/** What the battle screen draws for one side; updated only by battle events so HP/sprites animate in order. */
class SideView {
    var mon by mutableStateOf<BattleMon?>(null)
    var visible by mutableStateOf(false)
    var hidden by mutableStateOf(false)
    var displayHp by mutableIntStateOf(0)
    var maxHp by mutableIntStateOf(1)
    var statusTick by mutableIntStateOf(0)
    val hpAnim = Anim(0f)
    val enter = Anim(0f)
    val faint = Anim(0f)
    val shake = Anim(0f)
    val lunge = Anim(0f)
    var flashUp by mutableStateOf<Boolean?>(null)
    /** continuous idle bob phase (0..1 looping), advanced by the UI's frame loop */
    var idle by mutableFloatStateOf(0f)
    /** a substitute doll is standing in for this Pokémon */
    var substitute by mutableStateOf(false)
    /** 0..1 swap/pop animation progress for the substitute doll */
    val subAnim = Anim(0f)
}

/**
 * Bridges the suspend-based engine and the Compose UI: the engine awaits each event's presentation
 * and the player's choices; the opponent is driven by [FactoryAi].
 */
class BattleSession(
    val data: GameData,
    playerParty: List<FactoryMon>,
    foeParty: List<FactoryMon>,
    val trainerLabel: String,
    seed: Long,
    /** file name under assets/sprites/battle_bg */
    val background: String = "facility_0",
) {
    private val rng = BattleRandom(seed)
    private val ai = FactoryAi(rng)
    val views = arrayOf(SideView(), SideView())
    var message by mutableStateOf("")
    var mode by mutableStateOf(InputMode.NONE)
    var weather by mutableStateOf(Weather.NONE)
    /** Message/animation speed: 0 slow, 1 normal, 2 fast. */
    var speed by mutableIntStateOf(1)
    val fast get() = speed == 2
    private val msgDelay get() = when (speed) { 0 -> 1700L; 2 -> 350L; else -> 1000L }
    private val animScale get() = when (speed) { 0 -> 1.4f; 2 -> 0.5f; else -> 1f }

    /** The currently playing move effect, drawn procedurally over the scene. */
    var effect by mutableStateOf<MoveEffect?>(null)

    private var tap: CompletableDeferred<Unit>? = null
    private var actionReply: CompletableDeferred<Action>? = null
    private var replaceReply: CompletableDeferred<Int>? = null

    fun onTap() { tap?.complete(Unit) }

    /** Notified when the player changes the speed, so the setting can be persisted across battles. */
    var onSpeedChanged: ((Int) -> Unit)? = null
    fun cycleSpeed() { speed = (speed + 1) % 3; onSpeedChanged?.invoke(speed) }
    val speedLabel get() = when (speed) { 0 -> "느림"; 2 -> "빠름"; else -> "보통" }

    fun choose(a: Action) {
        mode = InputMode.NONE
        actionReply?.complete(a)
    }

    fun chooseReplacement(i: Int) {
        mode = InputMode.NONE
        replaceReply?.complete(i)
    }

    val playerController: BattleController = object : BattleController {
        override suspend fun event(e: BattleEvent) = present(e)
        override suspend fun chooseAction(battle: Battle, side: Int): Action {
            message = "${Josa.eunNeun(battle.sides[0].mon.name)} 무엇을 할까?"
            val d = CompletableDeferred<Action>()
            actionReply = d
            mode = InputMode.COMMAND
            return d.await()
        }
        override suspend fun chooseReplacement(battle: Battle, side: Int): Int {
            val d = CompletableDeferred<Int>()
            replaceReply = d
            message = "다음 포켓몬을 골라 주세요."
            mode = InputMode.REPLACE
            return d.await()
        }
    }

    private val aiController: BattleController = object : BattleController {
        override suspend fun event(e: BattleEvent) = Unit
        override suspend fun chooseAction(battle: Battle, side: Int) = ai.chooseAction(battle, side)
        override suspend fun chooseReplacement(battle: Battle, side: Int) = ai.chooseReplacement(battle, side)
    }

    val battle = Battle(
        data,
        arrayOf(
            Side(0, playerParty.map { BattleMon(data, MonInfo(data, it), 0) }, "나"),
            Side(1, foeParty.map { BattleMon(data, MonInfo(data, it), 1) }, trainerLabel),
        ),
        arrayOf(playerController, aiController),
        rng,
    )
    val player get() = battle.sides[0]

    private suspend fun waitMessage() {
        val d = CompletableDeferred<Unit>()
        tap = d
        withTimeoutOrNull(msgDelay) { d.await() }
        tap = null
    }

    private suspend fun present(e: BattleEvent) {
        when (e) {
            is BattleEvent.Message -> { message = e.text; waitMessage() }
            is BattleEvent.SendOut -> {
                val v = views[e.side]
                v.mon = e.mon
                v.displayHp = e.mon.hp; v.maxHp = e.mon.maxHp
                v.hpAnim.snapTo(e.mon.hp.toFloat() / e.mon.maxHp)
                v.hidden = false
                v.faint.snapTo(0f); v.enter.snapTo(0f)
                v.substitute = false; v.subAnim.snapTo(0f)
                v.visible = true
                v.statusTick++
                v.enter.animateTo(1f, (350 * animScale).toInt().coerceAtLeast(1))
            }
            is BattleEvent.Withdraw -> {
                val v = views[e.side]
                v.enter.animateTo(0f, (250 * animScale).toInt().coerceAtLeast(1))
                v.visible = false
                v.substitute = false; v.subAnim.snapTo(0f)
            }
            is BattleEvent.Hp -> {
                val v = views[e.side]
                val target = e.to.toFloat() / e.max
                val steps = 20
                val dur = (550 * animScale).toLong()
                coroutineScope {
                    launch { v.hpAnim.animateTo(target, dur.toInt()) }
                    launch {
                        for (i in 1..steps) {
                            v.displayHp = e.from + (e.to - e.from) * i / steps
                            delay(dur / steps)
                        }
                        v.displayHp = e.to
                    }
                }
            }
            is BattleEvent.StatusChanged -> views[e.side].statusTick++
            is BattleEvent.Faint -> {
                val v = views[e.side]
                v.faint.animateTo(1f, 450)
                v.visible = false
            }
            is BattleEvent.UseMove -> {
                val v = views[e.side]
                v.lunge.snapTo(0f)
                val d = (120 * animScale).toInt().coerceAtLeast(1)
                v.lunge.animateTo(1f, d); v.lunge.animateTo(0f, d)
                // Play a type/category-based effect travelling toward the target, unless it's a self move.
                if (!e.move.targetsSelf && !e.move.targetsField) {
                    val eff = MoveEffect(from = e.side, to = 1 - e.side, type = e.move.type, category = e.move.category)
                    effect = eff
                    eff.anim.animateTo(1f, (520 * animScale).toInt().coerceAtLeast(1))
                    if (effect === eff) effect = null
                }
            }
            is BattleEvent.Hit -> {
                val v = views[e.side]
                val d = (45 * animScale).toInt().coerceAtLeast(1)
                repeat(if (e.effectiveness > 4) 4 else 3) {
                    v.shake.animateTo(1f, d); v.shake.animateTo(0f, d)
                }
            }
            is BattleEvent.StatAnim -> {
                val v = views[e.side]
                v.flashUp = e.up
                delay((350 * animScale).toLong())
                v.flashUp = null
            }
            is BattleEvent.Hidden -> views[e.side].hidden = e.hidden
            is BattleEvent.WeatherChanged -> weather = e.weather
            is BattleEvent.Substitute -> {
                val v = views[e.side]
                if (e.up) {
                    // Pokémon ducks out, the doll pops in.
                    v.lunge.snapTo(0f)
                    v.lunge.animateTo(1f, (120 * animScale).toInt().coerceAtLeast(1))
                    v.substitute = true
                    v.subAnim.snapTo(0f)
                    v.subAnim.animateTo(1f, (260 * animScale).toInt().coerceAtLeast(1))
                    v.lunge.animateTo(0f, (120 * animScale).toInt().coerceAtLeast(1))
                } else {
                    // doll shatters, Pokémon returns.
                    v.subAnim.animateTo(0f, (220 * animScale).toInt().coerceAtLeast(1))
                    v.substitute = false
                }
            }
        }
    }
}
