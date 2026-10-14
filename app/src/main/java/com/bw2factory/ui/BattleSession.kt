package com.bw2factory.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
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

enum class InputMode { NONE, COMMAND, FIGHT, PARTY, REPLACE }

/** What the battle screen draws for one side; updated only by battle events so HP/sprites animate in order. */
class SideView {
    var mon by mutableStateOf<BattleMon?>(null)
    var visible by mutableStateOf(false)
    var hidden by mutableStateOf(false)
    var displayHp by mutableIntStateOf(0)
    var maxHp by mutableIntStateOf(1)
    var statusTick by mutableIntStateOf(0)
    val hpAnim = Animatable(0f)
    val enter = Animatable(0f)
    val faint = Animatable(0f)
    val shake = Animatable(0f)
    val lunge = Animatable(0f)
    var flashUp by mutableStateOf<Boolean?>(null)
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
) {
    private val rng = BattleRandom(seed)
    private val ai = FactoryAi(rng)
    val views = arrayOf(SideView(), SideView())
    var message by mutableStateOf("")
    var mode by mutableStateOf(InputMode.NONE)
    var weather by mutableStateOf(Weather.NONE)
    var fast by mutableStateOf(false)

    private var tap: CompletableDeferred<Unit>? = null
    private var actionReply: CompletableDeferred<Action>? = null
    private var replaceReply: CompletableDeferred<Int>? = null

    fun onTap() { tap?.complete(Unit) }

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
        withTimeoutOrNull(if (fast) 450L else 1100L) { d.await() }
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
                v.visible = true
                v.statusTick++
                v.enter.animateTo(1f, tween(350))
            }
            is BattleEvent.Withdraw -> {
                val v = views[e.side]
                v.enter.animateTo(0f, tween(250))
                v.visible = false
            }
            is BattleEvent.Hp -> {
                val v = views[e.side]
                val target = e.to.toFloat() / e.max
                val steps = 20
                coroutineScope {
                    launch { v.hpAnim.animateTo(target, tween(if (fast) 250 else 550)) }
                    launch {
                        for (i in 1..steps) {
                            v.displayHp = e.from + (e.to - e.from) * i / steps
                            delay((if (fast) 250L else 550L) / steps)
                        }
                        v.displayHp = e.to
                    }
                }
            }
            is BattleEvent.StatusChanged -> views[e.side].statusTick++
            is BattleEvent.Faint -> {
                val v = views[e.side]
                v.faint.animateTo(1f, tween(450))
                v.visible = false
            }
            is BattleEvent.UseMove -> {
                val v = views[e.side]
                v.lunge.snapTo(0f)
                v.lunge.animateTo(1f, tween(120)); v.lunge.animateTo(0f, tween(120))
            }
            is BattleEvent.Hit -> {
                val v = views[e.side]
                repeat(if (e.effectiveness > 4) 4 else 3) {
                    v.shake.animateTo(1f, tween(45)); v.shake.animateTo(0f, tween(45))
                }
            }
            is BattleEvent.StatAnim -> {
                val v = views[e.side]
                v.flashUp = e.up
                delay(350)
                v.flashUp = null
            }
            is BattleEvent.Hidden -> views[e.side].hidden = e.hidden
            is BattleEvent.WeatherChanged -> weather = e.weather
        }
    }
}
