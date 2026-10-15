package com.usumfactory.engine

import com.usumfactory.data.FactorySet
import com.usumfactory.data.GameData
import kotlinx.serialization.Serializable

object Types {
    const val NORMAL = 0
    const val FIGHTING = 1
    const val FLYING = 2
    const val POISON = 3
    const val GROUND = 4
    const val ROCK = 5
    const val BUG = 6
    const val GHOST = 7
    const val STEEL = 8
    const val FIRE = 9
    const val WATER = 10
    const val GRASS = 11
    const val ELECTRIC = 12
    const val PSYCHIC = 13
    const val ICE = 14
    const val DRAGON = 15
    const val DARK = 16
    const val FAIRY = 17
    const val NULL = 18

    /**
     * Gen 6+ type chart (USUM): attacking type (row) vs defending type (column), in halves:
     * 0 immune, 2 half, 4 normal, 8 double. Column/row order matches the Types constants above,
     * ending with Fairy (17). Extends the Gen 5 (pokebw2) chart with the Fairy type.
     */
    private val CHART = arrayOf(
        //        Nor Fig Fly Poi Gro Roc Bug Gho Ste Fir Wat Gra Ele Psy Ice Dra Dar Fai
        intArrayOf(4,  4,  4,  4,  4,  2,  4,  0,  2,  4,  4,  4,  4,  4,  4,  4,  4,  4), // Normal
        intArrayOf(8,  4,  2,  2,  4,  8,  2,  0,  8,  4,  4,  4,  4,  2,  8,  4,  8,  2), // Fighting
        intArrayOf(4,  8,  4,  4,  4,  2,  8,  4,  2,  4,  4,  8,  2,  4,  4,  4,  4,  4), // Flying
        intArrayOf(4,  4,  4,  2,  2,  2,  4,  2,  0,  4,  4,  8,  4,  4,  4,  4,  4,  8), // Poison
        intArrayOf(4,  4,  0,  8,  4,  8,  2,  4,  8,  8,  4,  2,  8,  4,  4,  4,  4,  4), // Ground
        intArrayOf(4,  2,  8,  4,  2,  4,  8,  4,  2,  8,  4,  4,  4,  4,  8,  4,  4,  4), // Rock
        intArrayOf(4,  2,  2,  2,  4,  4,  4,  2,  2,  2,  4,  8,  4,  8,  4,  4,  8,  2), // Bug
        intArrayOf(0,  4,  4,  4,  4,  4,  4,  8,  2,  4,  4,  4,  4,  8,  4,  4,  2,  4), // Ghost
        intArrayOf(4,  4,  4,  4,  4,  8,  4,  4,  2,  2,  2,  4,  2,  4,  8,  4,  4,  8), // Steel
        intArrayOf(4,  4,  4,  4,  4,  2,  8,  4,  8,  2,  2,  8,  4,  4,  8,  2,  4,  4), // Fire
        intArrayOf(4,  4,  4,  4,  8,  8,  4,  4,  4,  8,  2,  2,  4,  4,  4,  2,  4,  4), // Water
        intArrayOf(4,  4,  2,  2,  8,  8,  2,  4,  2,  2,  8,  2,  4,  4,  4,  2,  4,  4), // Grass
        intArrayOf(4,  4,  8,  4,  0,  4,  4,  4,  4,  4,  8,  2,  2,  4,  4,  2,  4,  4), // Electric
        intArrayOf(4,  8,  4,  8,  4,  4,  4,  4,  2,  4,  4,  4,  4,  2,  4,  4,  0,  4), // Psychic
        intArrayOf(4,  4,  8,  4,  8,  4,  4,  4,  2,  2,  2,  8,  4,  4,  2,  8,  4,  4), // Ice
        intArrayOf(4,  4,  4,  4,  4,  4,  4,  4,  2,  4,  4,  4,  4,  4,  4,  8,  4,  0), // Dragon
        intArrayOf(4,  2,  4,  4,  4,  4,  4,  8,  2,  4,  4,  4,  4,  8,  4,  4,  2,  2), // Dark
        intArrayOf(4,  8,  4,  2,  4,  4,  4,  4,  2,  2,  4,  4,  4,  4,  4,  8,  8,  4), // Fairy
    )

    /** 0, 2, 4 or 8 (halves); TYPE_NULL on either side is neutral. */
    fun single(atk: Int, def: Int): Int = if (atk >= NULL || def >= NULL) 4 else CHART[atk][def]
}

/** Stage indices into BattleMon.stages; also the order natures use (Atk, Def, Spe, SpA, SpD). */
object Stat {
    const val HP = 0
    const val ATK = 1
    const val DEF = 2
    const val SPE = 3
    const val SPA = 4
    const val SPD = 5
    const val ACC = 6
    const val EVA = 7

    fun parse(s: String) = when (s) {
        "ATK" -> ATK; "DEF" -> DEF; "SPE" -> SPE; "SPA" -> SPA; "SPD" -> SPD
        "ACC" -> ACC; "EVA" -> EVA; else -> -1
    }

    val NAMES_KO = arrayOf("HP", "공격", "방어", "스피드", "특수공격", "특수방어", "명중률", "회피율")
}

enum class Status { NONE, SLEEP, POISON, TOXIC, BURN, FREEZE, PARALYSIS }

/** HGSS LCRandom: seed = seed * 0x41C64E6D + 0x6073, returns the high 16 bits. Used for Factory generation. */
class LcRandom(var seed: Long) {
    fun next(): Int {
        seed = (seed * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
        return ((seed ushr 16) and 0xFFFF).toInt()
    }
}

/** Battle randomness (MATH_Rand32 in BW2); kept seedable for tests. */
class BattleRandom(seed: Long) {
    private val r = java.util.Random(seed)
    /** 0 until n-1 */
    fun next(n: Int): Int = if (n <= 1) 0 else r.nextInt(n)
    fun chance(percent: Int): Boolean = next(100) < percent
}

/**
 * A rental/opponent Pokémon built from a Factory set: one IV for all stats, EVs split 510 across flagged stats,
 * a PID that matches the set's nature, ability by PID parity (pokeheartgold src/unk_0204B538.c sub_0204B834).
 */
@Serializable
data class FactoryMon(
    val setId: Int,
    val iv: Int,
    val pid: Long,
    val level: Int = 100,
)

class MonInfo(val data: GameData, val mon: FactoryMon) {
    val set: FactorySet = data.sets.getValue(mon.setId)
    val species = data.species.getValue(set.species)
    val ability: Int = species.abilities.let { a -> if (a.size > 1 && mon.pid % 2 == 1L) a[1] else a[0] }
    val gender: Int = when (species.genderRatio) {
        255 -> 2
        254 -> 1
        0 -> 0
        else -> if ((mon.pid and 0xFF) < species.genderRatio) 1 else 0
    }
    val evs: IntArray = run {
        val n = set.evs.count { it }
        val ev = if (n == 0) 0 else minOf(255, 510 / n)
        IntArray(6) { if (set.evs[it]) ev else 0 }
    }
    val stats: IntArray = IntArray(6) { i ->
        val b = species.base[i]
        val lv = mon.level
        val core = (2 * b + mon.iv + evs[i] / 4) * lv / 100
        if (i == Stat.HP) core + lv + 10
        else {
            val raw = core + 5
            val nature = set.nature
            val plus = 1 + nature / 5
            val minus = 1 + nature % 5
            when {
                plus == minus -> raw
                i == plus -> raw * 110 / 100
                i == minus -> raw * 90 / 100
                else -> raw
            }
        }
    }
    val friendship: Int get() = if (set.moves.contains(218)) 0 else 255 // Frustration -> 0
}

/** Korean particle helper: picks 이/가, 은/는, 을/를, 와/과, (으)로 by the final consonant. */
object Josa {
    private fun hasBatchim(word: String): Boolean {
        val c = word.trimEnd().lastOrNull() ?: return false
        if (c in '가'..'힣') return (c - '가') % 28 != 0
        if (c.isDigit()) return c in "013678"
        if (c.isLetter()) return c.lowercaseChar() in "lmnr"
        return false
    }

    fun iGa(w: String) = w + if (hasBatchim(w)) "이" else "가"
    fun eunNeun(w: String) = w + if (hasBatchim(w)) "은" else "는"
    fun eulReul(w: String) = w + if (hasBatchim(w)) "을" else "를"
    fun waGwa(w: String) = w + if (hasBatchim(w)) "과" else "와"
    fun ro(w: String): String {
        val c = w.trimEnd().lastOrNull()
        val rieul = c != null && c in '가'..'힣' && (c - '가') % 28 == 8
        return w + if (hasBatchim(w) && !rieul) "으로" else "로"
    }
}
