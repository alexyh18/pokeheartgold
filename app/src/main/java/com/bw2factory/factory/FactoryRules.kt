package com.bw2factory.factory

import com.bw2factory.data.GameData
import com.bw2factory.engine.FactoryMon
import com.bw2factory.engine.LcRandom

/**
 * Battle Subway generation rules for Super Single, ported from pokebw2:
 *  - trainer choice:  src/ov033/bsubway_scr.c func_ov033_0217c11c (sSuperTrainerRanges / sSuperLastTrainerRanges /
 *                     sSuperTopTrainerRange), level = streak / 7; boss (Ingo) at car 6 of level 6.
 *  - set draw & IV:   src/ov012/fld_btl_inst_tool.c func_ov012_0216292c + func_ov012_02162828 (IV by trainerId).
 *  - mon build:       func_ov012_02162490 (EV split, one IV for all stats, nature from the set, ability by PID).
 *
 * Super Single is level 50, three Pokémon per side. A "round" here is one 7-car train.
 */
object FactoryRules {
    const val BATTLES_PER_ROUND = 7
    const val LEVEL = 50
    const val TEAM_SIZE = 3
    /** order.json trainer index of the Super Single boss (subway_boss_88_ingo). */
    const val BOSS_INGO = 307

    private class Range(val min: Int, val max: Int)

    /** sSuperTrainerRanges: cars 0–5 by level. */
    private val SUPER_CARS = arrayOf(Range(110, 159), Range(140, 179), Range(160, 199), Range(180, 219))
    /** sSuperLastTrainerRanges: car 6 by level. */
    private val SUPER_LAST = arrayOf(Range(160, 179), Range(180, 199), Range(200, 219), Range(220, 239))
    /** sSuperTopTrainerRange: level >= 4. */
    private val SUPER_TOP = Range(200, 299)

    fun battleNumber(round: Int, battleInRound: Int) = 7 * round + battleInRound + 1

    /** func_ov012_02162828: IV (all six stats) by trainerId. */
    fun ivForTrainer(trainerId: Int): Int = when {
        trainerId < 50 -> 3
        trainerId < 70 -> 6
        trainerId < 90 -> 9
        trainerId < 110 -> 12
        trainerId < 160 -> 15
        trainerId < 180 -> 18
        trainerId < 200 -> 21
        else -> 31
    }

    /** func_ov033_0217c11c for Super Single (mode 5). index = car (0..6), level = streak/7. */
    fun pickTrainer(rng: LcRandom, level: Int, car: Int): Int {
        if (car == 6 && level == 6) return BOSS_INGO
        val range = when {
            level < 4 -> if (car < 6) SUPER_CARS[level] else SUPER_LAST[level]
            else -> SUPER_TOP
        }
        return range.min + rng.next() % (range.max - range.min + 1)
    }

    /** func_ov033_0217b9dc: seven distinct trainers for one train. */
    fun pickRoundTrainers(rng: LcRandom, round: Int): List<Int> {
        val level = round
        val out = mutableListOf<Int>()
        var guard = 0
        while (out.size < BATTLES_PER_ROUND) {
            val t = pickTrainer(rng, level, out.size)
            if (t !in out || guard++ > 400) out += t
        }
        return out
    }

    /**
     * func_ov012_0216292c: draw [count] sets from the trainer's eligible sets, rejecting duplicate species and
     * (within 50 retries) duplicate items, and avoiding the excluded species/items.
     */
    fun pickSets(
        data: GameData,
        rng: LcRandom,
        trainerSets: List<Int>,
        count: Int,
        excludeSpecies: List<Int>,
        excludeItems: List<Int>,
    ): List<Int> {
        val picks = mutableListOf<Int>()
        var retries = 0
        var guard = 0
        while (picks.size < count && guard++ < 10000) {
            val id = trainerSets[rng.next() % trainerSets.size]
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

    /** func_ov012_02162490: nature-matched PID is not required in BW2 — PID is generated, nature is set directly. */
    fun buildMon(rng: LcRandom, setId: Int, iv: Int): FactoryMon {
        val pid = rng.next().toLong() or (rng.next().toLong() shl 16)
        return FactoryMon(setId = setId, iv = iv, pid = pid, level = LEVEL)
    }

    /** The player's rental choices: six sets drawn from the whole eligible pool for this streak level. */
    fun rentals(data: GameData, rng: LcRandom, round: Int, trades: Int): List<FactoryMon> {
        // Rentals use the same tier as the opponents of this train (the first car's trainer range + IV).
        val sampleTrainer = pickTrainer(LcRandom(rng.seed), round, 0)
        val iv = ivForTrainer(sampleTrainer)
        val pool = (0 until 1000).filter { data.sets[it]?.species?.let { s -> s != 0 } == true }
        val picks = pickSets(data, rng, pool, 6, emptyList(), emptyList())
        return picks.map { buildMon(rng, it, iv) }
    }

    /** The opponent trainer's team. */
    fun opponents(data: GameData, rng: LcRandom, trainerId: Int, trainerSets: List<Int>, playerParty: List<FactoryMon>): List<FactoryMon> {
        val iv = ivForTrainer(trainerId)
        val sp = playerParty.map { data.sets.getValue(it.setId).species }
        val items = playerParty.map { data.sets.getValue(it.setId).item }
        val picks = pickSets(data, rng, trainerSets, TEAM_SIZE, sp, items)
        return picks.map { buildMon(rng, it, iv) }
    }
}
