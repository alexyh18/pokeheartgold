package com.bw2factory.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bw2factory.data.GameData
import com.bw2factory.engine.Josa
import com.bw2factory.factory.Factory
import com.bw2factory.factory.FactoryRules
import com.bw2factory.factory.FactoryState
import com.bw2factory.factory.OpponentHint
import com.bw2factory.factory.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the challenge is saved (SharedPreferences on device). */
interface StateStore {
    fun read(): String?
    fun write(text: String)
}

class PrefsStore(context: Context) : StateStore {
    private val prefs = context.getSharedPreferences("battle_factory", Context.MODE_PRIVATE)
    override fun read() = prefs.getString(KEY, null)
    override fun write(text: String) { prefs.edit().putString(KEY, text).apply() }
    private companion object { const val KEY = "state_v1" }
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
    /** The Battle Subway boss (Ingo) at car 7 of the 7th train. */
    val isBoss get() = state.trainerId == FactoryRules.BOSS_INGO

    /** Hint about the next opponent (null when no battle is coming, e.g. after a loss). */
    val hint: OpponentHint?
        get() = state.takeIf { it.phase in setOf(Phase.RENTAL, Phase.PRE_BATTLE, Phase.SWAP) }
            ?.let { OpponentHint.of(data, it.round, it.opponent) }

    /** BW2 battle background: the subway car stage (changing each train) or the boss's night stage. */
    private fun backgroundFor(s: FactoryState) =
        if (isBoss) "head" else "facility_${s.round % 5}"

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
