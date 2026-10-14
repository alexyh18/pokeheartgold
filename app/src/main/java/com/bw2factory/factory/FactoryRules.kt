package com.bw2factory.factory

import com.bw2factory.data.GameData
import com.bw2factory.engine.FactoryMon
import com.bw2factory.engine.LcRandom

/**
 * Battle Factory generation rules for single battles, Open Level, ported from pokeheartgold overlay 80:
 *  - trainer choice:      ov80_02236B78 / ov80_02236BE4, table ov80_0223C478
 *  - set-range rows:      ov80_02236C78 (rentals, ov80_0223C4B8) and ov80_02236C2C (opponents, ov80_0223C558)
 *  - set picker:          ov80_02236C9C (next-tier mixing by ov80_0223C464, inclusive draw, species/item dedupe)
 *  - mon build:           src/unk_0204B538.c sub_0204B834 (nature-matched PID, one IV for all stats)
 */
object FactoryRules {
    const val BATTLES_PER_ROUND = 7
    const val LEVEL = 100
    const val THORTON_SILVER = 309
    const val THORTON_GOLD = 310

    /** {normMin, normMax, bossMin, bossMax}, max exclusive; index = round (clamped to 7). */
    private val TRAINER_RANGES = arrayOf(
        intArrayOf(0, 99, 100, 119), intArrayOf(80, 119, 120, 139), intArrayOf(100, 139, 140, 159),
        intArrayOf(120, 159, 160, 179), intArrayOf(140, 179, 180, 199), intArrayOf(160, 199, 200, 219),
        intArrayOf(180, 219, 220, 239), intArrayOf(200, 299, 200, 299),
    )

    class Row(val key: Int, val min: Int, val max: Int, val iv: Int, val mixNextTier: Boolean)

    /** ov80_0223C4B8: Open Level rental rows, by round. */
    private val RENTAL_ROWS = listOf(
        Row(100, 351, 486, 0, true), Row(120, 487, 622, 4, true), Row(140, 623, 758, 8, true),
        Row(160, 759, 894, 12, true), Row(180, 623, 950, 16, true), Row(200, 623, 950, 20, true),
        Row(220, 623, 950, 24, true), Row(300, 351, 950, 31, false), Row(310, 351, 486, 12, false),
        Row(311, 759, 950, 31, false),
    )

    /** ov80_0223C558: Open Level opponent rows, by trainer id (first row with key > id). */
    private val OPPONENT_ROWS = listOf(
        Row(100, 351, 486, 0, true), Row(120, 487, 622, 4, true), Row(140, 623, 758, 8, true),
        Row(160, 759, 894, 12, true), Row(180, 351, 950, 16, true), Row(200, 351, 950, 20, true),
        Row(220, 351, 950, 24, true), Row(300, 351, 950, 31, false), Row(310, 351, 486, 12, false),
        Row(311, 759, 950, 31, false),
    )

    /** ov80_0223C464 */
    private val MIX_THRESHOLDS = intArrayOf(6, 13, 20, 27, 34)

    fun battleNumber(round: Int, battleInRound: Int) = 7 * minOf(round, 7) + battleInRound + 1

    /** ov80_02236B78 (mode 0 = singles). */
    fun pickTrainer(rng: LcRandom, round: Int, battleInRound: Int): Int {
        val r = minOf(round, 7)
        val n = 7 * r + battleInRound + 1
        if (n == 21) return THORTON_SILVER
        if (n == 49) return THORTON_GOLD
        val t = TRAINER_RANGES[r]
        val (min, max) = if (battleInRound == 6 || battleInRound == 13) t[2] to t[3] else t[0] to t[1]
        return min + rng.next() % (max - min)
    }

    /** ov80_02236BE4: seven distinct trainers for a round. */
    fun pickRoundTrainers(rng: LcRandom, round: Int): List<Int> {
        val out = mutableListOf<Int>()
        var guard = 0
        while (out.size < BATTLES_PER_ROUND) {
            val t = pickTrainer(rng, round, out.size)
            if (t !in out || guard++ > 200) out += t
        }
        return out
    }

    fun rentalRow(round: Int) = RENTAL_ROWS[minOf(round, 7)]

    fun opponentRow(trainer: Int): Row = OPPONENT_ROWS.firstOrNull { trainer < it.key } ?: OPPONENT_ROWS.last()

    /**
     * ov80_02236C9C: picks [count] sets from [row]; the last k picks come from the next row when mixing is on,
     * where k grows with [mixCounter]. Rejects duplicate species/items among picks and against [exclude].
     */
    fun pickSets(
        data: GameData,
        rng: LcRandom,
        row: Int,
        rows: List<Row>,
        count: Int,
        mixCounter: Int,
        excludeSpecies: List<Int>,
        excludeItems: List<Int>,
    ): List<Pair<Int, Int>> {
        val r = rows[row]
        var k = 0
        if (mixCounter != 0) {
            k = 5
            for (i in MIX_THRESHOLDS.indices) if (mixCounter <= MIX_THRESHOLDS[i]) { k = i; break }
        }
        val picks = mutableListOf<Pair<Int, Int>>() // set id, iv
        var guard = 0
        while (picks.size < count) {
            val useNext = picks.size >= count - k && r.mixNextTier && row + 1 < rows.size
            val src = if (useNext) rows[row + 1] else r
            val id = src.max - rng.next() % (src.max - src.min + 1)
            val set = data.sets[id] ?: continue // non-Gen 4 sets are not exported
            guard++
            val strict = guard < 5000
            val dupe = picks.any { (pid, _) ->
                val p = data.sets.getValue(pid)
                p.species == set.species || (strict && p.item == set.item)
            }
            if (dupe) continue
            if (set.species in excludeSpecies || (strict && set.item in excludeItems)) continue
            picks += id to src.iv
        }
        return picks
    }

    /** sub_0204B834: PID rerolled until it matches the set's nature. */
    fun buildMon(data: GameData, rng: LcRandom, setId: Int, iv: Int): FactoryMon {
        val nature = data.sets.getValue(setId).nature
        var pid: Long
        do {
            pid = rng.next().toLong() or (rng.next().toLong() shl 16)
        } while (pid % 25 != nature.toLong())
        return FactoryMon(setId = setId, iv = iv, pid = pid, level = LEVEL)
    }

    fun rentals(data: GameData, rng: LcRandom, round: Int, trades: Int): List<FactoryMon> {
        val row = minOf(round, 7)
        return pickSets(data, rng, row, RENTAL_ROWS, 6, trades, emptyList(), emptyList())
            .map { (id, iv) -> buildMon(data, rng, id, iv) }
    }

    fun opponents(data: GameData, rng: LcRandom, trainer: Int, playerParty: List<FactoryMon>): List<FactoryMon> {
        val row = OPPONENT_ROWS.indexOf(opponentRow(trainer))
        val sp = playerParty.map { data.sets.getValue(it.setId).species }
        val items = playerParty.map { data.sets.getValue(it.setId).item }
        return pickSets(data, rng, row, OPPONENT_ROWS, 3, 0, sp, items)
            .map { (id, iv) -> buildMon(data, rng, id, iv) }
    }
}
