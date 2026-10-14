package com.bw2factory.factory

import com.bw2factory.data.GameData
import com.bw2factory.engine.FactoryMon
import com.bw2factory.engine.LcRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Phase { RENTAL, PRE_BATTLE, SWAP, ROUND_CLEAR, LOST }

/** Everything needed to resume a Battle Factory challenge (single battles, Open Level). */
@Serializable
data class FactoryState(
    val phase: Phase = Phase.RENTAL,
    /** wins in the current challenge */
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** swaps made in the current challenge (drives next-tier rentals, ov80_02236C9C) */
    val trades: Int = 0,
    val totalTrades: Int = 0,
    val roundsCleared: Int = 0,
    val rentals: List<FactoryMon> = emptyList(),
    val party: List<FactoryMon> = emptyList(),
    val roundTrainers: List<Int> = emptyList(),
    /** the next opponent's party; generated early so its hint can be shown while choosing Pokémon */
    val opponent: List<FactoryMon> = emptyList(),
    /** the Pokémon of the trainer just beaten, offered on the swap screen */
    val swapOffer: List<FactoryMon> = emptyList(),
    val seed: Long = 0,
    /** last challenge's final streak, shown on the loss screen */
    val lastResult: Int = 0,
) {
    val round get() = streak / FactoryRules.BATTLES_PER_ROUND
    val battleInRound get() = streak % FactoryRules.BATTLES_PER_ROUND
    val trainerId get() = roundTrainers.getOrElse(battleInRound) { 0 }
    val battleNumber get() = streak + 1
}

/** Pure state transitions; the UI persists every returned state. */
class Factory(private val data: GameData) {
    private fun rng(s: FactoryState) = LcRandom(s.seed)

    private fun FactoryState.withRng(block: (LcRandom) -> FactoryState): FactoryState {
        val r = rng(this)
        val out = block(r)
        return out.copy(seed = r.seed)
    }

    /** A brand-new challenge from round 1: six rentals to choose from. */
    fun newChallenge(prev: FactoryState?, seed: Long = System.nanoTime()): FactoryState {
        val base = FactoryState(
            seed = seed and 0xFFFFFFFFL,
            bestStreak = prev?.bestStreak ?: 0,
            totalTrades = prev?.totalTrades ?: 0,
            roundsCleared = prev?.roundsCleared ?: 0,
            lastResult = prev?.lastResult ?: 0,
        )
        return startRound(base)
    }

    /**
     * New rentals and trainers for the round. The first opponent is generated now, avoiding the species and
     * items of all six rentals (the original avoids the chosen three; it rolls the opponent after the pick).
     */
    private fun startRound(s: FactoryState): FactoryState = s.withRng { r ->
        val rentals = FactoryRules.rentals(data, r, s.round, s.trades)
        val trainers = FactoryRules.pickRoundTrainers(r, s.round)
        val n = s.copy(phase = Phase.RENTAL, rentals = rentals, roundTrainers = trainers, party = emptyList(), swapOffer = emptyList())
        n.copy(opponent = FactoryRules.opponents(data, r, n.trainerId, rentals))
    }

    /** picks: indices into rentals, in party order (first is the lead). */
    fun chooseRentals(s: FactoryState, picks: List<Int>): FactoryState {
        require(picks.size == 3 && picks.distinct().size == 3)
        val party = picks.map { s.rentals[it] }
        return s.copy(phase = Phase.PRE_BATTLE, party = party, rentals = emptyList())
    }

    /** Rolls the next opponent, avoiding the given Pokémon (the party plus anything the player may still take). */
    private fun rollOpponent(s: FactoryState, avoid: List<FactoryMon>): FactoryState = s.withRng { r ->
        s.copy(opponent = FactoryRules.opponents(data, r, s.trainerId, avoid))
    }

    /** Brings saves from v1.0 (opponent rolled at battle prep, offer kept in `opponent`) to this flow. */
    fun normalize(s: FactoryState): FactoryState = when {
        s.phase == Phase.SWAP && s.swapOffer.isEmpty() -> rollOpponent(s.copy(swapOffer = s.opponent), s.party + s.opponent)
        s.phase == Phase.RENTAL && s.opponent.isEmpty() -> rollOpponent(s, s.rentals)
        s.phase == Phase.PRE_BATTLE && s.opponent.isEmpty() -> rollOpponent(s, s.party)
        else -> s
    }

    /** The UI may reorder the party before a battle (the first one leads). */
    fun reorder(s: FactoryState, order: List<Int>) = s.copy(party = order.map { s.party[it] })

    fun battleWon(s: FactoryState): FactoryState {
        val streak = s.streak + 1
        val best = maxOf(s.bestStreak, streak)
        val n = s.copy(streak = streak, bestStreak = best)
        return if (streak % FactoryRules.BATTLES_PER_ROUND == 0) {
            n.copy(phase = Phase.ROUND_CLEAR, roundsCleared = s.roundsCleared + 1, opponent = emptyList(), swapOffer = emptyList())
        } else {
            rollOpponent(n.copy(phase = Phase.SWAP, swapOffer = s.opponent), s.party + s.opponent)
        }
    }

    fun battleLost(s: FactoryState): FactoryState =
        s.copy(phase = Phase.LOST, lastResult = s.streak, bestStreak = maxOf(s.bestStreak, s.streak))

    /** Swap party[mine] for swapOffer[theirs]; null = keep the team. The next opponent is already rolled. */
    fun swap(s: FactoryState, mine: Int?, theirs: Int?): FactoryState {
        val n = if (mine != null && theirs != null) {
            val party = s.party.toMutableList()
            party[mine] = s.swapOffer[theirs].copy()
            s.copy(party = party, trades = s.trades + 1, totalTrades = s.totalTrades + 1)
        } else s
        return n.copy(phase = Phase.PRE_BATTLE, swapOffer = emptyList())
    }

    fun continueAfterRound(s: FactoryState): FactoryState = startRound(s)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(s: FactoryState) = json.encodeToString(FactoryState.serializer(), s)
        fun decode(text: String): FactoryState? = runCatching { json.decodeFromString(FactoryState.serializer(), text) }.getOrNull()
    }
}
