package com.usumfactory.factory

import com.usumfactory.data.GameData
import com.usumfactory.engine.FactoryMon
import com.usumfactory.engine.LcRandom

/**
 * USUM Battle Tree generation rules.
 *  - Pool: every Battle Tree set (cristiannomartins/Tree-Sets data). The player picks 3 rentals,
 *    builds a win streak, and may swap one Pokémon with the trainer just beaten.
 *  - Trainers appear only within their Battle Tree streak window ([start, end], 1-based battle numbers),
 *    which is how the game ramps difficulty: later battles field higher-tier trainers.
 *  - Battle Tree Pokémon have maxed IVs; here all six IVs are 31.
 *  - A "round" of 7 battles drives the swap/round-clear cadence (purely a UI grouping).
 */
object FactoryRules {
    const val BATTLES_PER_ROUND = 7
    /** Battle Tree (normal) runs at Level 50. */
    const val LEVEL = 50
    const val TEAM_SIZE = 3
    const val IV = 31

    fun battleNumber(round: Int, battleInRound: Int) = BATTLES_PER_ROUND * round + battleInRound + 1

    /** Trainers whose Battle Tree streak window contains [battleNumber] (1-based). */
    private fun eligibleTrainers(data: GameData, battleNumber: Int): List<Int> {
        val pool = data.trainers.filter { battleNumber in it.start..it.end }.map { it.id }
        return pool.ifEmpty { data.trainers.map { it.id } }
    }

    /** One trainer valid for the given battle number, avoiding [used] when possible. */
    fun pickTrainer(data: GameData, rng: LcRandom, battleNumber: Int, used: List<Int> = emptyList()): Int {
        val pool = eligibleTrainers(data, battleNumber)
        val fresh = pool.filter { it !in used }.ifEmpty { pool }
        return fresh[rng.next() % fresh.size]
    }

    /** Seven distinct trainers for one round, each valid for its own battle number. */
    fun pickRoundTrainers(data: GameData, rng: LcRandom, round: Int): List<Int> {
        val out = mutableListOf<Int>()
        for (i in 0 until BATTLES_PER_ROUND) {
            out += pickTrainer(data, rng, battleNumber(round, i), out)
        }
        return out
    }

    /**
     * Draw [count] sets from [pool], rejecting duplicate species and (within 50 retries) duplicate items,
     * and avoiding the excluded species/items.
     */
    fun pickSets(
        data: GameData,
        rng: LcRandom,
        pool: List<Int>,
        count: Int,
        excludeSpecies: List<Int>,
        excludeItems: List<Int>,
    ): List<Int> {
        val use = pool.filter { data.isEligible(it) }.ifEmpty { data.superSetIds }
        if (use.isEmpty()) return emptyList()
        val picks = mutableListOf<Int>()
        var retries = 0
        var guard = 0
        while (picks.size < count && guard++ < 10000) {
            val id = use[rng.next() % use.size]
            val set = data.sets[id] ?: continue
            if (set.species == 0) continue
            if (picks.any { data.sets.getValue(it).species == set.species }) continue
            if (set.species in excludeSpecies) continue
            if (retries < 50) {
                val dupItem = picks.any { data.sets.getValue(it).item != 0 && data.sets.getValue(it).item == set.item } ||
                    (set.item != 0 && set.item in excludeItems)
                if (dupItem) { retries++; continue }
            }
            picks += id
        }
        return picks
    }

    fun buildMon(rng: LcRandom, setId: Int): FactoryMon {
        val pid = rng.next().toLong() or (rng.next().toLong() shl 16)
        return FactoryMon(setId = setId, iv = IV, pid = pid, level = LEVEL)
    }

    /** The player's rental choices: six sets drawn from the whole Battle Tree pool. */
    fun rentals(data: GameData, rng: LcRandom, round: Int, trades: Int): List<FactoryMon> {
        val picks = pickSets(data, rng, data.superSetIds, 6, emptyList(), emptyList())
        return picks.map { buildMon(rng, it) }
    }

    /**
     * The opponent trainer's team: three Battle Tree Pokémon from the shared pool, avoiding the player's
     * current species and items (Battle Tree trainers draw from the common set pool, not fixed lists).
     */
    fun opponents(data: GameData, rng: LcRandom, trainerId: Int, trainerSets: List<Int>, playerParty: List<FactoryMon>): List<FactoryMon> {
        val sp = playerParty.map { data.sets.getValue(it.setId).species }
        val items = playerParty.map { data.sets.getValue(it.setId).item }
        val picks = pickSets(data, rng, data.superSetIds, TEAM_SIZE, sp, items)
        return picks.map { buildMon(rng, it) }
    }
}
