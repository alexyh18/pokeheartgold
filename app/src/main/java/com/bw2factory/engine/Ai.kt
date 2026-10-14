package com.bw2factory.engine

/**
 * Opponent AI: scores each usable move (damage vs. remaining HP, KO chances, accuracy, sensible status use)
 * in the spirit of the Frontier trainers' "basic + evaluate attack + expert" flags. Frontier trainers do not
 * switch voluntarily.
 */
class FactoryAi(private val rng: BattleRandom) {

    fun chooseAction(b: Battle, side: Int): Action {
        val m = b.sides[side].mon
        val t = b.sides[1 - side].mon
        val slots = b.usableSlots(m)
        if (slots.isEmpty()) return Action.Fight(-1)
        var best = slots[0]
        var bestScore = Int.MIN_VALUE
        for (i in slots) {
            val score = score(b, m, t, m.moves[i].move) + rng.next(12)
            if (score > bestScore) { bestScore = score; best = i }
        }
        return Action.Fight(best)
    }

    fun chooseReplacement(b: Battle, side: Int): Int {
        val s = b.sides[side]
        val foe = b.sides[1 - side].mon
        val opts = s.switchTargets()
        return opts.maxByOrNull { idx ->
            val c = s.party[idx]
            val myBest = c.moves.maxOfOrNull { b.estimateDamage(c, foe, it.move) * 100 / maxOf(1, foe.hp) } ?: 0
            val theirBest = foe.moves.maxOfOrNull { b.estimateDamage(foe, c, it.move) * 100 / maxOf(1, c.hp) } ?: 0
            myBest - theirBest / 2 + rng.next(10)
        } ?: opts.first()
    }

    private fun score(b: Battle, m: BattleMon, t: BattleMon, mv: com.bw2factory.data.MoveData): Int {
        val e = mv.effect
        if (!mv.isStatus) {
            val dmg = b.estimateDamage(m, t, mv)
            if (dmg <= 0 && e !in setOf("COUNTER", "MIRROR_COAT", "METAL_BURST")) return -100
            val pct = dmg * 100 / maxOf(1, t.hp)
            var s = minOf(pct, 100)
            if (dmg >= t.hp) s += 40
            val acc = if (mv.accuracy > 100) 100 else mv.accuracy
            s = s * acc / 100
            if (dmg >= t.hp && mv.priority > 0) s += 30
            when (e) {
                "RECHARGE_AFTER" -> if (dmg < t.hp) s -= 25
                "USER_SP_ATK_DOWN_2", "LOWER_OWN_ATK_AND_DEF" -> if (dmg < t.hp) s -= 5
                "HALVE_DEFENSE" -> s = if (m.hp * 3 < m.maxHp && dmg >= t.hp / 2) s else -50
                "COUNTER", "MIRROR_COAT", "METAL_BURST" -> s = 15
                "ONE_HIT_KO" -> s = 25
                "ALWAYS_FLINCH_FIRST_TURN_ONLY" -> s = if (m.turnsOnField == 0) s + 30 else -100
                "HIT_FIRST_IF_TARGET_ATTACKING" -> s -= 5
                "HIT_LAST_WHIFF_IF_HIT" -> if (t.substituteHp == 0) s -= 30
                "RECOVER_DAMAGE_SLEEP" -> if (t.status != Status.SLEEP) s = -100
                "FAIL_IF_NOT_USED_ALL_OTHER_MOVES" -> if (m.moves.any { it.move.id != mv.id && it.move.id !in m.usedMoves }) s = -100
                "SPIT_UP" -> if (m.stockpile == 0) s = -100
                "FLING" -> if (b.item(m) == It.NONE) s = -100
                "SWITCH_HIT" -> s += 5
            }
            return s
        }
        val hpPct = m.hp * 100 / m.maxHp
        val tFree = t.status == Status.NONE && t.substituteHp == 0
        return when (e) {
            "STATUS_SLEEP" -> if (tFree) 70 else -50
            "STATUS_SLEEP_NEXT_TURN" -> if (tFree && t.yawnTurns == 0) 45 else -50
            "STATUS_PARALYZE" -> if (tFree && !(mv.key == "thunder_wave" && b.effectiveness(mv.type, m, t) == 0)) 50 else -50
            "STATUS_BURN" -> if (tFree && !t.hasType(Types.FIRE)) (if (t.stats[Stat.ATK] > t.stats[Stat.SPA]) 60 else 35) else -50
            "STATUS_BADLY_POISON" -> if (tFree && !t.hasType(Types.POISON) && !t.hasType(Types.STEEL)) 50 else -50
            "STATUS_CONFUSE", "ATK_UP_2_STATUS_CONFUSION" -> if (t.confusion == 0 && t.substituteHp == 0) 30 else -50
            "STATUS_LEECH_SEED" -> if (!t.leechSeeded && !t.hasType(Types.GRASS)) 40 else -50
            "RESTORE_HALF_HP", "HEAL_HALF_MORE_IN_SUN", "HEAL_HALF_REMOVE_FLYING_TYPE" -> if (hpPct < 45) 75 else if (hpPct < 70) 25 else -50
            "REST" -> if (hpPct < 35 || (m.status != Status.NONE && hpPct < 60)) 60 else -50
            "SWALLOW" -> if (m.stockpile > 0 && hpPct < 50) 50 else -50
            "PROTECT" -> if (m.protectCount == 0) 12 else -50
            "SURVIVE_WITH_1_HP" -> if (m.protectCount == 0 && hpPct < 30) 15 else -50
            "SET_SUBSTITUTE" -> if (m.substituteHp == 0 && hpPct > 50) 30 else -50
            "SET_REFLECT" -> if (b.sideOf(m).reflect == 0) 25 else -50
            "SET_LIGHT_SCREEN" -> if (b.sideOf(m).lightScreen == 0) 25 else -50
            "SET_SPIKES" -> if (b.sides[1 - m.side].spikes < 3 && b.sides[1 - m.side].alive() > 1) 30 else -50
            "TOXIC_SPIKES" -> if (b.sides[1 - m.side].toxicSpikes < 2 && b.sides[1 - m.side].alive() > 1) 25 else -50
            "STEALTH_ROCK" -> if (!b.sides[1 - m.side].stealthRock && b.sides[1 - m.side].alive() > 1) 35 else -50
            "WEATHER_SUN" -> if (b.weather != Weather.SUN) 20 else -50
            "WEATHER_RAIN" -> if (b.weather != Weather.RAIN) 20 else -50
            "WEATHER_SANDSTORM" -> if (b.weather != Weather.SAND) 15 else -50
            "WEATHER_HAIL" -> if (b.weather != Weather.HAIL) 15 else -50
            "TRICK_ROOM" -> if (b.trickRoom == 0 && b.speed(m) < b.speed(t)) 40 else -50
            "MAX_ATK_LOSE_HALF_MAX_HP" -> if (hpPct > 70 && m.stages[Stat.ATK] < 2) 45 else -50
            "ATK_UP", "ATK_UP_2", "DEF_UP_2", "SPEED_UP_2", "SP_DEF_UP_2", "SP_ATK_UP_2", "EVA_UP", "ATK_DEF_UP",
            "ATK_SPD_UP", "SP_ATK_SP_DEF_UP", "DEF_SPD_UP", "EVA_UP_2_MINIMIZE", "SP_DEF_UP_DOUBLE_ELECTRIC_POWER", "STOCKPILE", "RANDOM_STAT_UP_2" -> {
                val total = mv.stats.sumOf { m.stages[Stat.parse(it.stat).coerceAtLeast(1)] }
                if (hpPct > 60 && total < 2) 40 - total * 10 else -30
            }
            "ACC_DOWN", "ATK_DOWN_2", "DEF_DOWN_2", "SPEED_DOWN_2", "SP_DEF_DOWN_2" -> if (t.substituteHp == 0) 15 else -50
            "TAUNT" -> if (t.tauntTurns == 0) 20 else -50
            "ENCORE" -> if (t.lastMove?.isStatus == true && t.encoreMove == null) 45 else -50
            "DISABLE" -> if (t.lastMove != null && t.disabledMove == null) 20 else -50
            "INFATUATE" -> if (!t.infatuated && m.gender != 2 && t.gender != 2 && m.gender != t.gender) 30 else -50
            "SWITCH_HELD_ITEMS" -> if (It.isChoice(b.item(m))) 40 else 5
            "FORCE_SWITCH" -> if ((1..5).any { t.stages[it] > 0 }) 45 else 5
            "PASS_STATS_AND_STATUS" -> if ((1..5).sumOf { m.stages[it] } >= 2) 40 else -50
            "COPY_STAT_CHANGES" -> if ((1..5).sumOf { t.stages[it] } >= 2) 40 else -50
            "AVERAGE_HP" -> if (t.hp > m.hp * 2) 45 else -50
            "FAINT_AND_ATK_SP_ATK_DOWN_2" -> if (hpPct < 25) 30 else -50
            "KO_MON_THAT_DEFEATED_USER" -> if (hpPct < 25) 35 else -50
            "ALL_FAINT_3_TURNS" -> if (t.perishCount < 0) 10 else -50
            "CURSE" -> if (m.hasType(Types.GHOST)) (if (!t.cursed && hpPct > 60) 25 else -50) else (if (m.stages[Stat.ATK] < 2) 35 else -30)
            "SUPRESS_ABILITY" -> if (!t.abilitySuppressed) 10 else -50
            "GROUND_TRAP_USER_CONTINUOUS_HEAL", "RESTORE_HP_EVERY_TURN" -> if (!m.ingrained && !m.aquaRing) 20 else -50
            "GIVE_GROUND_IMMUNITY" -> if (m.magnetRiseTurns == 0 && t.moves.any { it.move.type == Types.GROUND && !it.move.isStatus }) 35 else -50
            "SWAP_ATK_DEF" -> if (!m.powerTrick && m.stats[Stat.DEF] > m.stats[Stat.ATK]) 30 else -50
            "RECYCLE" -> if (m.item == It.NONE && m.itemConsumed) 25 else -50
            "HIT_IN_3_TURNS" -> if (b.sides[1 - m.side].futureSightTurns == 0) 30 else -50
            else -> 8
        }
    }
}
