package com.usumfactory.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usumfactory.data.GameData
import com.usumfactory.engine.Josa
import com.usumfactory.factory.Factory
import com.usumfactory.factory.FactoryRules
import com.usumfactory.factory.FactoryState
import com.usumfactory.factory.OpponentHint
import com.usumfactory.factory.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the challenge and settings are saved (SharedPreferences on device). */
interface StateStore {
    fun read(): String?
    fun write(text: String)
    /** Persisted battle-speed setting (0 slow, 1 normal, 2 fast); -1 if never set. */
    fun readSpeed(): Int
    fun writeSpeed(value: Int)
}

class PrefsStore(context: Context) : StateStore {
    private val prefs = context.getSharedPreferences("battle_factory", Context.MODE_PRIVATE)
    override fun read() = prefs.getString(KEY, null)
    override fun write(text: String) { prefs.edit().putString(KEY, text).apply() }
    override fun readSpeed() = prefs.getInt(SPEED_KEY, -1)
    override fun writeSpeed(value: Int) { prefs.edit().putInt(SPEED_KEY, value).apply() }
    private companion object { const val KEY = "state_v1"; const val SPEED_KEY = "battle_speed" }
}

class GameViewModel(val data: GameData, val sprites: Sprites, private val store: StateStore) : ViewModel() {
    private val factory = Factory(data)

    /** Saved progress; a fresh install starts straight at round 1's rental selection. */
    var state by mutableStateOf(load())
        private set
    var session by mutableStateOf<BattleSession?>(null)
        private set
    /** Post-battle banner, e.g. "승부에서 이겼다!" */
    var battleResult by mutableStateOf<String?>(null)
        private set

    /** Battle-speed setting, persisted across battles (0 slow, 1 normal, 2 fast; default normal). */
    var battleSpeed = store.readSpeed().let { if (it in 0..2) it else 1 }
        private set

    fun setBattleSpeed(value: Int) {
        battleSpeed = value.coerceIn(0, 2)
        store.writeSpeed(battleSpeed)
        session?.speed = battleSpeed
    }

    private fun load(): FactoryState {
        val saved = store.read()?.let { Factory.decode(it) }?.let { factory.normalize(it) }
        return (saved ?: factory.newChallenge(null)).also { save(it) }
    }

    private fun save(s: FactoryState) {
        store.write(Factory.encode(s))
    }

    private fun update(s: FactoryState) {
        state = s
        save(s)
    }

    fun trainer(id: Int = state.trainerId) = data.trainers[id]
    fun trainerLabel(id: Int = state.trainerId) = trainer(id).let { "${it.classKo} ${it.name}" }
    /** The Battle Tree has no fixed boss in this build. */
    val isBoss get() = false

    /** Hint about the next opponent (null when no battle is coming, e.g. after a loss). */
    val hint: OpponentHint?
        get() = state.takeIf { it.phase in setOf(Phase.RENTAL, Phase.PRE_BATTLE, Phase.SWAP) }
            ?.let { OpponentHint.of(data, it.round, it.opponent) }

    /** Battle Tree background (code-drawn gradient fallback when the asset is absent). */
    private fun backgroundFor(s: FactoryState) = "tree_${s.round % 3}"

    fun chooseRentals(picks: List<Int>) = update(factory.chooseRentals(state, picks))
    fun reorder(order: List<Int>) = update(factory.reorder(state, order))
    fun swap(mine: Int?, theirs: Int?) = update(factory.swap(state, mine, theirs))
    fun nextRound() = update(factory.continueAfterRound(state))
    fun newChallenge() = update(factory.newChallenge(state))

    /** Give up the current streak and start over from train 1. The best-streak record is kept. */
    fun forfeit() = update(factory.newChallenge(state))

    fun startBattle() {
        if (session != null) return
        val s = state
        val label = trainerLabel()
        val bs = BattleSession(data, s.party, s.opponent, label, System.nanoTime(), backgroundFor(s))
        bs.speed = battleSpeed
        bs.onSpeedChanged = { setBattleSpeed(it) }
        session = bs
        battleResult = null
        viewModelScope.launch {
            val winner = bs.battle.run()
            val won = winner == 0
            bs.mode = InputMode.NONE
            bs.message = if (won) "${Josa.waGwa(label)}의 승부에서 이겼다!" else "${Josa.waGwa(label)}의 승부에서 졌다…"
            delay(1800)
            update(if (won) factory.battleWon(state) else factory.battleLost(state))
            battleResult = if (won) "승리" else "패배"
            session = null
        }
    }

    companion object {
        fun create(app: Application): GameViewModel {
            val data = GameData.load { path -> app.assets.open(path).bufferedReader().use { it.readText() } }
            return GameViewModel(data, Sprites(app.assets), PrefsStore(app))
        }
    }
}

val Phase.title: String
    get() = when (this) {
        Phase.RENTAL -> "렌탈 포켓몬 선택"
        Phase.PRE_BATTLE -> "다음 상대"
        Phase.SWAP -> "포켓몬 교환"
        Phase.ROUND_CLEAR -> "라운드 클리어"
        Phase.LOST -> "도전 종료"
    }
