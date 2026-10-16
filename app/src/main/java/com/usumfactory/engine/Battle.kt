package com.usumfactory.engine

import com.usumfactory.data.GameData
import com.usumfactory.data.MoveData

sealed interface Action {
    /** slot = move slot index, or -1 for Struggle. mega/zMove request Mega Evolution / a Z-move this turn. */
    data class Fight(val slot: Int, val mega: Boolean = false, val zMove: Boolean = false) : Action
    data class Switch(val index: Int) : Action
}

sealed interface BattleEvent {
    data class Message(val text: String) : BattleEvent
    data class SendOut(val side: Int, val mon: BattleMon) : BattleEvent
    data class Withdraw(val side: Int) : BattleEvent
    data class Hp(val side: Int, val from: Int, val to: Int, val max: Int) : BattleEvent
    data class StatusChanged(val side: Int) : BattleEvent
    data class Faint(val side: Int) : BattleEvent
    data class UseMove(val side: Int, val move: MoveData) : BattleEvent
    /** The attack motion actually plays: emitted only once a move has passed all miss/fail checks. */
    data class Attack(val side: Int, val move: MoveData) : BattleEvent
    /** quarter multiplier: 0, 1, 2, 4, 8, 16 */
    data class Hit(val side: Int, val effectiveness: Int) : BattleEvent
    data class StatAnim(val side: Int, val up: Boolean) : BattleEvent
    data class Hidden(val side: Int, val hidden: Boolean) : BattleEvent
    data class WeatherChanged(val weather: Weather) : BattleEvent
    data class TerrainChanged(val terrain: Terrain) : BattleEvent
    /** A substitute doll appeared (up = true) or broke (up = false) on [side]. */
    data class Substitute(val side: Int, val up: Boolean) : BattleEvent
    /** [side]'s Pokémon Mega-Evolved (sprite/name refresh + flash). */
    data class MegaEvolve(val side: Int, val mon: BattleMon) : BattleEvent
    /** [side] unleashed a Z-move. */
    data class ZMove(val side: Int, val move: MoveData) : BattleEvent
}

interface BattleController {
    suspend fun event(e: BattleEvent)
    suspend fun chooseAction(battle: Battle, side: Int): Action
    /** Returns the party index to send in. */
    suspend fun chooseReplacement(battle: Battle, side: Int): Int
}

class Side(val index: Int, val party: List<BattleMon>, val trainerLabel: String) {
    var active = 0
    val mon: BattleMon get() = party[active]
    var reflect = 0
    var lightScreen = 0
    var spikes = 0
    var toxicSpikes = 0
    var stealthRock = false
    var tailwind = 0
    var safeguard = 0
    var mist = 0
    var healingWish = false
    var futureSightTurns = 0
    var futureSightUser: BattleMon? = null
    var futureSightMove: MoveData? = null
    /** Mega Evolution and Z-moves are each limited to once per battle per trainer (this side). */
    var usedMega = false
    var usedZ = false
    fun switchTargets() = party.indices.filter { it != active && !party[it].fainted }
    fun alive() = party.count { !it.fainted }
}

class BattleOver : Exception()

/**
 * A 3-vs-3 single battle with Gen 5 (BW2) mechanics.
 * Formulas follow pokebw2 src/ov167/btl_server_flow.c and btl_calc.c (damage, crits, accuracy, speed, turn order).
 */
class Battle(
    val data: GameData,
    val sides: Array<Side>,
    private val controllers: Array<BattleController>,
    val rng: BattleRandom,
) {
    var weather = Weather.NONE
    var weatherTurns = 0
    var terrain = Terrain.NONE
    var terrainTurns = 0
    var trickRoom = 0
    var gravityTurns = 0
    var turn = 0
    var winner = -1
        private set
    private var lastMoveUsedInBattle: MoveData? = null
    private val actionsThisTurn = arrayOfNulls<Action>(2)

    /** Moves that ignore Protect/Detect (Gen 7). */
    private val protectBypassMoves = setOf(
        "feint", "phantom_force", "shadow_force", "hyperspace_hole", "hyperspace_fury",
    )

    /** Powder/spore moves (Gen 6+: Grass-types are immune). Rage Powder's redirection is a no-op in a
     *  1v1 battle, so it's covered here for completeness but has no additional effect. */
    private val powderMoves = setOf(
        "sleep_powder", "stun_spore", "poison_powder", "spore", "cotton_spore", "rage_powder", "powder",
    )

    fun foe(m: BattleMon) = sides[1 - m.side].mon
    fun sideOf(m: BattleMon) = sides[m.side]

    // ------------------------------------------------------------------ text helpers
    // Uses displayName so an Illusion (일루전) shows its disguise in every battle message.
    fun nm(m: BattleMon) = if (m.side == 1) "상대 ${m.displayName}" else m.displayName
    private fun mvName(m: MoveData) = m.nameKo
    /**
     * The move's display name, substituting the Gen 7 Z-move name while [user] is unleashing it as a
     * Z-move. Damaging Z-moves become their type's Z-move (e.g. 불꽃 → 다이내믹풀플레임); status Z-moves
     * keep their own name, matching the games.
     */
    private fun mvName(user: BattleMon, move: MoveData): String {
        if (user.zMoveNameActive && !move.isStatus) {
            data.zMoveNameByType[moveType(user, move)]?.let { return it }
        }
        return move.nameKo
    }
    private fun itName(i: Int) = data.itemName(i)
    private fun abName(a: Int) = data.abilityName(a)

    private suspend fun msg(text: String) = emit(BattleEvent.Message(text))
    private suspend fun emit(e: BattleEvent) {
        controllers[0].event(e)
        controllers[1].event(e)
    }

    // ------------------------------------------------------------------ ability / item / weather queries
    fun ability(m: BattleMon) = if (m.abilitySuppressed) Ab.NONE else m.ability

    /** The defender's ability as seen by an attacker (Mold Breaker ignores breakable abilities). */
    private fun defAbility(att: BattleMon, def: BattleMon): Int {
        val a = ability(def)
        return if (ability(att) == Ab.MOLD_BREAKER && a in Ab.BREAKABLE && att !== def) Ab.NONE else a
    }

    fun item(m: BattleMon) = if (ability(m) == Ab.KLUTZ) It.NONE else m.item

    fun effWeather(): Weather = weather

    fun grounded(m: BattleMon): Boolean {
        if (item(m) == It.IRON_BALL || m.ingrained) return true
        if (m.hasType(Types.FLYING)) return false
        if (ability(m) == Ab.LEVITATE) return false
        if (m.magnetRiseTurns > 0) return false
        if (item(m) == It.AIR_BALLOON) return false
        return true
    }

    /** Stat changes for boost moves whose data has none (effect name encodes them). */
    private fun statlessBoost(e: String): List<com.usumfactory.data.StatChangeData> = when (e) {
        "QUIVER_DANCE" -> listOf(sc("SPA", 1), sc("SPD", 1), sc("SPE", 1))
        "SHIFT_GEAR" -> listOf(sc("ATK", 1), sc("SPE", 2))
        "COIL" -> listOf(sc("ATK", 1), sc("DEF", 1), sc("ACC", 1))
        "HONE_CLAWS" -> listOf(sc("ATK", 1), sc("ACC", 1))
        "WORK_UP" -> listOf(sc("ATK", 1), sc("SPA", 1))
        "COTTON_GUARD" -> listOf(sc("DEF", 3))
        "DEF_UP_DOUBLE_ROLLOUT_POWER" -> listOf(sc("DEF", 1))
        else -> emptyList()
    }

    private fun sc(stat: String, stages: Int) = com.usumfactory.data.StatChangeData(stat, stages, 0)

    private fun stageMul(v: Int, stage: Int): Int {
        val s = stage.coerceIn(-6, 6)
        return if (s >= 0) v * (2 + s) / 2 else v * 2 / (2 - s)
    }

    fun speed(m: BattleMon): Int {
        var s = stageMul(m.stats[Stat.SPE], m.stages[Stat.SPE])
        var r = 0x1000
        val w = effWeather()
        when (ability(m)) {
            Ab.SWIFT_SWIM -> if (w == Weather.RAIN) r = chain(r, 0x2000)
            Ab.CHLOROPHYLL -> if (w == Weather.SUN) r = chain(r, 0x2000)
            Ab.SAND_RUSH -> if (w == Weather.SAND) r = chain(r, 0x2000)
            Ab.QUICK_FEET -> if (m.status != Status.NONE) r = chain(r, 0x1800)
            Ab.UNBURDEN -> if (m.item == It.NONE && m.lostItem != 0) r = chain(r, 0x2000)
            Ab.SLOW_START -> if (m.slowStartTurns < 5) r = chain(r, 0x800)
        }
        if (sideOf(m).tailwind > 0) r = chain(r, 0x2000)
        when (item(m)) {
            It.CHOICE_SCARF -> r = chain(r, 0x1800)
            It.IRON_BALL -> r = chain(r, 0x800)
        }
        s = fixedRound(s, r)
        if (m.status == Status.PARALYSIS && ability(m) != Ab.QUICK_FEET) s = s * 50 / 100  // Gen 7: paralysis halves Speed
        s = s.coerceAtMost(10000)
        return s
    }

    private fun chain(a: Int, b: Int) = ((a.toLong() * b + 0x800) shr 12).toInt()

    /** pokebw2 fixed_round: v * r / 4096, rounding up only when the fraction is above one half. */
    private fun fixedRound(v: Int, r: Int): Int = ((v.toLong() * r + 0x7FF) shr 12).toInt()

    /** Effectiveness in quarters: 0, 1, 2, 4, 8, 16 */
    fun effectiveness(moveType: Int, att: BattleMon?, def: BattleMon): Int {
        var q = 4
        for (t in def.effectiveTypes) {
            var e = Types.single(moveType, t)
            if (e == 0 && t == Types.GHOST && (moveType == Types.NORMAL || moveType == Types.FIGHTING) &&
                (def.foresighted || (att != null && ability(att) == Ab.SCRAPPY))) e = 4
            // Iron Ball / Ingrain ground a Flying type: Ground moves then hit it normally.
            if (e == 0 && t == Types.FLYING && moveType == Types.GROUND && grounded(def)) e = 4
            q = q * e / 4
        }
        if (moveType == Types.GROUND && q > 0 && !grounded(def)) q = 0
        return q
    }

    /**
     * Effectiveness label of [move] used by [user] against the current foe, for the move-select UI.
     * Returns one of -2 (x1/4 or less), -1 (x1/2), 0 (x1 / status / no info), 1 (x2), 2 (x4), or null if immune.
     */
    /**
     * Type-matchup tier shown in the fight menu. Deliberately uses ONLY the type chart (including
     * fixed type-based immunities like Ghost vs Normal/Fighting and Flying vs Ground) and does NOT
     * reveal anything that depends on the opponent's ability — e.g. Levitate/부유, Water Absorb/저수,
     * Flash Fire, Lightning Rod, Sap Sipper, Dry Skin, Storm Drain, Motor Drive — because the player
     * cannot normally know the foe's ability in advance.
     */
    fun moveEffectivenessTier(user: BattleMon, move: MoveData): Int? {
        if (move.isStatus) return 0
        val t = foe(user)
        if (t.fainted) return 0
        val type = moveType(user, move)
        var q = 4
        for (dt in t.effectiveTypes) q = q * Types.single(type, dt) / 4
        // Note: no grounded()/Levitate check here — a Ground move's "no effect" against Levitate
        // must stay hidden. Flying's native Ground immunity is already in the type chart above.
        return when {
            q == 0 -> null
            q <= 1 -> -2
            q == 2 -> -1
            q == 4 -> 0
            q == 8 -> 1
            else -> 2
        }
    }

    fun moveType(user: BattleMon, move: MoveData): Int {
        when (move.effect) {
            "CHANGE_TYPE_WITH_WEATHER" -> return when (effWeather()) {
                Weather.SUN -> Types.FIRE
                Weather.RAIN -> Types.WATER
                Weather.HAIL -> Types.ICE
                Weather.SAND -> Types.ROCK
                Weather.NONE -> Types.NORMAL
            }
            // Hidden Power: type is derived from the user's IVs (see BattleMon.hiddenPowerType).
            "RANDOM_POWER_BASED_ON_IVS" -> return user.hiddenPowerType
            // Natural Gift: type comes from the held berry (Normal + fails if no berry).
            "NATURAL_GIFT" -> return naturalGift(item(user))?.first ?: Types.NORMAL
        }
        // "-ate"/skin abilities (Aerilate/Pixilate/Refrigerate/Galvanize) turn a Normal-type move into
        // the ability's type. The matching 1.2x power boost is applied in movePower(). Moves whose type
        // is derived elsewhere (handled by the when above) already returned, so this only affects true
        // Normal-type moves.
        if (move.type == Types.NORMAL && !move.isStatus) {
            Ab.ATE_TYPE[ability(user)]?.let { return it }
        }
        return move.type
    }

    /** Natural Gift: held-berry item id -> (type, base power). Null if the item is not a usable berry. */
    private fun naturalGift(itemId: Int): Pair<Int, Int>? = It.NATURAL_GIFT[itemId]

    // ------------------------------------------------------------------ legal moves
    fun usableSlots(m: BattleMon): List<Int> = m.moves.indices.filter { i ->
        val s = m.moves[i]
        s.pp > 0 &&
            s.move.id != m.disabledMove?.id &&
            !(m.tauntTurns > 0 && s.move.isStatus) &&
            !(m.tormented && m.lastMove?.id == s.move.id) &&
            (m.choiceMove == null || item(m).let { It.isChoice(it) }.not() || m.choiceMove?.id == s.move.id) &&
            (m.encoreMove == null || m.encoreMove?.id == s.move.id)
    }

    fun canSwitch(m: BattleMon): Boolean {
        val s = sideOf(m)
        if (s.switchTargets().isEmpty()) return false
        return !isTrapped(m)
    }

    private fun isTrapped(m: BattleMon): Boolean {
        // Gen 6+: Ghost-types can always flee/switch, ignoring Mean Look, Block, binding moves,
        // Arena Trap, Magnet Pull, Shadow Tag, etc.
        if (m.hasType(Types.GHOST)) return false
        if (m.trapped || m.bindTurns > 0 || m.ingrained) return true
        val f = foe(m)
        if (f.fainted) return false
        val fa = ability(f)
        if (fa == Ab.ARENA_TRAP && grounded(m)) return true
        if (fa == Ab.MAGNET_PULL && m.hasType(Types.STEEL)) return true
        return false
    }

    /** True when this turn's move for the mon is forced (no menu). */
    fun forcedAction(m: BattleMon): Boolean =
        m.recharging || m.lockedMove != null || m.chargingMove != null

    // ------------------------------------------------------------------ main loop
    /** Runs the battle to the end and returns the winning side (0 = player). */
    suspend fun run(): Int {
        try {
            val foeSide = sides[1]
            setupIllusion(foeSide.mon)
            setupIllusion(sides[0].mon)
            msg("${Josa.eunNeun(foeSide.trainerLabel)} ${Josa.eulReul(foeSide.mon.displayName)} 내보냈다!")
            emit(BattleEvent.SendOut(1, foeSide.mon))
            msg("가랏! ${sides[0].mon.displayName}!")
            emit(BattleEvent.SendOut(0, sides[0].mon))
            sides.forEach { it.mon.turnsOnField = 0 }
            for (m in bySpeed(listOf(sides[0].mon, sides[1].mon))) onEntry(m)
            while (true) {
                turn++
                playTurn()
                endOfTurn()
                replaceFainted()
            }
        } catch (_: BattleOver) {
        }
        return winner
    }

    private fun bySpeed(ms: List<BattleMon>): List<BattleMon> {
        val a = ms[0]
        val b = ms[1]
        val sa = speed(a)
        val sb = speed(b)
        val aFirst = if (sa == sb) rng.next(2) == 0 else (sa > sb) != (trickRoom > 0)
        return if (aFirst) listOf(a, b) else listOf(b, a)
    }

    private fun checkOver() {
        val p = sides[0].alive()
        val f = sides[1].alive()
        if (p == 0 || f == 0) {
            // If both sides run out at once (e.g. Explosion), the side that used the move loses in Gen 5; we
            // simply call it for the side that still has Pokémon, and for the opponent on a double KO.
            winner = if (f == 0 && p > 0) 0 else if (p == 0 && f > 0) 1 else 1
            throw BattleOver()
        }
    }

    private suspend fun playTurn() {
        for (s in sides) {
            val m = s.mon
            m.protecting = false; m.enduring = false; m.flinch = false
            m.movedThisTurn = false; m.tookDamageThisTurn = false; m.roosted = false
        }
        for (i in 0..1) {
            val m = sides[i].mon
            actionsThisTurn[i] = if (forcedAction(m)) Action.Fight(-2) else controllers[i].chooseAction(this, i)
        }
        // Focus Punch charge message comes before anything else.
        for (i in 0..1) {
            val a = actionsThisTurn[i]
            val m = sides[i].mon
            if (a is Action.Fight && a.slot >= 0 && m.moves[a.slot].move.effect == "HIT_LAST_WHIFF_IF_HIT") {
                m.focusPunchCharging = true
                msg("${Josa.eunNeun(nm(m))} 기합을 모으고 있다!")
            }
        }
        // Mega Evolution happens at the start of the turn, before move order (it can change Speed).
        for (i in 0..1) {
            val a = actionsThisTurn[i]
            if (a is Action.Fight && a.mega) megaEvolve(sides[i].mon)
        }
        // Switches first, faster first; Pursuit hits a switching target first.
        val order = orderActions()
        for ((side, action) in order) {
            if (action !is Action.Switch) continue
            val other = 1 - side
            val oa = actionsThisTurn[other]
            val om = sides[other].mon
            if (oa is Action.Fight && oa.slot >= 0 && !om.fainted && !om.movedThisTurn &&
                om.moves[oa.slot].move.effect == "HIT_BEFORE_SWITCH"
            ) {
                doMoveAction(om, oa, pursuitBoost = true)
                if (sides[side].mon.fainted) {
                    checkOver(); continue
                }
            }
            if (sides[side].mon.fainted) continue
            switchOut(side, action.index, announce = true)
        }
        for ((side, action) in order) {
            if (action !is Action.Fight) continue
            val m = sides[side].mon
            if (m.fainted || m.movedThisTurn) continue
            doMoveAction(m, action, pursuitBoost = false)
            checkOver()
        }
        sides.forEach { it.mon.focusPunchCharging = false }
    }

    private fun actionMove(m: BattleMon, a: Action.Fight): MoveData = when {
        a.slot == -2 -> m.lockedMove ?: m.chargingMove ?: m.lastMove ?: data.struggle
        a.slot == -1 -> data.struggle
        else -> m.moves[a.slot].move
    }

    private fun orderActions(): List<Pair<Int, Action>> {
        val entries = (0..1).map { it to actionsThisTurn[it]!! }
        fun key(side: Int, a: Action): Triple<Int, Int, Int> {
            val m = sides[side].mon
            if (a is Action.Switch) return Triple(7, 0, 0)
            val move = actionMove(m, a as Action.Fight)
            // Prankster (Gen7): the user's status moves get +1 priority (Dark targets are immune — handled at use).
            val prio = move.priority + if (ability(m) == Ab.PRANKSTER && move.isStatus) 1 else 0
            val quick = if (item(m) == It.QUICK_CLAW && rng.chance(20)) 1 else 0
            if (quick == 1) quickClawUsed[side] = true
            var spe = speed(m)
            if (trickRoom > 0) spe = 10000 - spe
            return Triple(prio, quick, spe)
        }
        quickClawUsed.fill(false)
        val k0 = key(0, entries[0].second)
        val k1 = key(1, entries[1].second)
        val cmp = compareValuesBy(k0, k1, { it.first }, { it.second }, { it.third })
        val first0 = if (cmp == 0) rng.next(2) == 0 else cmp > 0
        return if (first0) entries else entries.reversed()
    }

    private val quickClawUsed = BooleanArray(2)

    private suspend fun doMoveAction(m: BattleMon, a: Action.Fight, pursuitBoost: Boolean) {
        m.movedThisTurn = true
        if (quickClawUsed[m.side]) {
            quickClawUsed[m.side] = false
            msg("${Josa.eunNeun(nm(m))} 선제공격손톱으로 행동이 빨라졌다!")
        }
        val move: MoveData
        var slot: MoveSlot? = null
        if (a.slot == -2) {
            if (m.recharging) {
                m.recharging = false
                msg("${Josa.eunNeun(nm(m))} 공격의 반동으로 움직일 수 없다!")
                return
            }
            move = m.lockedMove ?: m.chargingMove ?: data.struggle
        } else if (a.slot == -1) {
            move = data.struggle
        } else {
            slot = m.moves[a.slot]
            move = if (m.encoreMove != null) m.encoreMove!! else slot.move
            if (m.encoreMove != null) slot = m.moves.firstOrNull { it.move.id == move.id } ?: slot
        }
        // Z-move: a matching Z-crystal turns one move into a Z-move (once per battle). A damaging Z-move
        // gets a boosted power; a status Z-move keeps its normal effect and gains a Z-bonus.
        val zStatus = a.zMove && canZMove(m, move) && move.isStatus
        if (a.zMove && canZMove(m, move)) {
            m.usedZ = true
            sideOf(m).usedZ = true           // one Z-move per battle per trainer
            m.zMoveActive = !move.isStatus   // power override only for damaging Z-moves
            m.zMoveNameActive = true         // name override for both damaging and status Z-moves
            msg("${Josa.eunNeun(nm(m))} Z파워를 해방했다!")
            emit(BattleEvent.ZMove(m.side, move))
        }
        useMove(m, move, slot, fromAction = true, pursuitBoost = pursuitBoost)
        m.zMoveActive = false
        m.zMoveNameActive = false
        if (zStatus && !m.fainted) zStatusBonus(m, move)
    }

    /**
     * The Z-bonus a status move grants when used as a Z-move (Gen 7). This is in addition to the move's
     * normal effect (which already resolved). Following the official Gen 7 Z-status table:
     *  - most buff moves grant an extra +1 to a specific stat,
     *  - healing moves fully restore HP,
     *  - a handful of signature moves get a special bonus (reset stats, +crit, cure status, etc.).
     * Moves not in the table grant no extra bonus.
     */
    private suspend fun zStatusBonus(m: BattleMon, move: MoveData) {
        // 1) Full-heal Z-bonus for recovery/healing moves.
        val healKeys = setOf(
            "recover", "roost", "synthesis", "moonlight", "morning_sun", "soft_boiled", "milk_drink",
            "slack_off", "heal_order", "shore_up", "rest", "wish", "swallow", "heal_pulse",
            "floral_healing", "aqua_ring", "ingrain",
        )
        if (move.key in healKeys) {
            if (m.hp < m.maxHp) {
                msg("${Josa.eunNeun(nm(m))} Z파워로 HP를 모두 회복했다!")
                heal(m, m.maxHp - m.hp)
            }
            return
        }
        // 2) Signature special bonuses.
        when (move.key) {
            "haze" -> {  // reset the user's lowered stats
                var reset = false
                for (st in 0..5) if (m.stages[st] < 0) { m.stages[st] = 0; reset = true }
                if (reset) { emit(BattleEvent.StatusChanged(m.side)); msg("${Josa.eunNeun(nm(m))} Z파워로 능력 하락을 원래대로 되돌렸다!") }
                return
            }
            "bestow", "celebrate", "conversion", "hold_hands" -> return
        }
        // 3) +1 stat Z-bonus. Mapped by the move's key, covering the full status-move pool.
        val stat = when (move.key) {
            // Speed
            "agility", "rock_polish", "autotomize", "tailwind" -> Stat.SPE
            // Attack
            "work_up", "swords_dance", "howl", "sharpen", "meditate", "dragon_dance", "shift_gear",
            "hone_claws", "bulk_up", "coil", "swagger" -> Stat.ATK
            // Defense
            "defense_curl", "harden", "withdraw", "iron_defense", "cotton_guard", "acid_armor",
            "barrier", "stockpile", "cosmic_power", "defend_order", "flower_shield" -> Stat.DEF
            // Special Attack
            "nasty_plot", "charge_beam", "flatter" -> Stat.SPA
            // Special Defense
            "calm_mind", "amnesia", "charge", "aromatic_mist" -> Stat.SPD
            // Evasion
            "double_team", "minimize" -> Stat.EVA
            // Accuracy
            "lock_on", "mind_reader", "foresight", "miracle_eye" -> Stat.ACC
            // Speed boost for multi-stat dance moves (Quiver Dance already raises Spe; Z grants +1 Spe)
            "quiver_dance" -> Stat.SPE
            else -> -1
        }
        if (stat >= 0 && m.stages[stat] < 6) {
            msg("${Josa.eunNeun(nm(m))} Z파워로 기운이 넘친다!")
            changeStage(m, stat, 1, m)
        }
    }

    /** A move (damaging or status) whose type matches the held Z-crystal, usable once per battle. */
    fun canZMove(m: BattleMon, move: MoveData): Boolean {
        if (m.usedZ || sideOf(m).usedZ) return false   // one Z-move per battle per trainer
        val zType = It.zCrystalType(m.item)
        return zType >= 0 && zType == moveType(m, move)
    }

    // ------------------------------------------------------------------ switching
    suspend fun switchOut(side: Int, index: Int, announce: Boolean, batonPass: Boolean = false) {
        val s = sides[side]
        val old = s.mon
        if (!old.fainted) {
            if (ability(old) == Ab.NATURAL_CURE && old.status != Status.NONE) {
                old.status = Status.NONE
            }
            if (ability(old) == Ab.REGENERATOR && old.hp < old.maxHp) {
                old.hp = minOf(old.maxHp, old.hp + old.maxHp / 3)
            }
            if (announce) msg(if (side == 0) "돌아와, ${old.name}!" else "${Josa.eunNeun(s.trainerLabel)} ${Josa.eulReul(old.name)} 넣어 버렸다!")
            emit(BattleEvent.Withdraw(side))
        }
        val passed = if (batonPass) Passed(old) else null
        old.resetVolatile()
        // Gen 5 resets the sleep counter on switch-out (it's re-rolled on the next sleep check).
        s.active = index
        val m = s.mon
        m.resetVolatile()
        passed?.applyTo(m)
        // A foe's binding ends when the binder leaves.
        val f = foe(m)
        if (f.bindTurns > 0) { f.bindTurns = 0 }
        if (f.trapped) f.trapped = false
        setupIllusion(m)
        if (side == 0) msg("가랏! ${m.displayName}!") else msg("${Josa.eunNeun(s.trainerLabel)} ${Josa.eulReul(m.displayName)} 내보냈다!")
        emit(BattleEvent.SendOut(side, m))
        entryHazards(m)
        if (!m.fainted) onEntry(m)
    }

    /** Baton Pass carries stat stages, confusion, substitute, focus, Leech Seed, Ingrain, Aqua Ring, Magnet Rise, Curse, Perish. */
    private class Passed(o: BattleMon) {
        val stages = o.stages.copyOf()
        val confusion = o.confusion
        val sub = o.substituteHp
        val seed = o.leechSeeded
        val ingrain = o.ingrained
        val aqua = o.aquaRing
        val rise = o.magnetRiseTurns
        val curse = o.cursed
        val perish = o.perishCount
        fun applyTo(m: BattleMon) {
            stages.copyInto(m.stages); m.confusion = confusion; m.substituteHp = sub; m.leechSeeded = seed
            m.ingrained = ingrain; m.aquaRing = aqua; m.magnetRiseTurns = rise; m.cursed = curse; m.perishCount = perish
        }
    }

    private suspend fun entryHazards(m: BattleMon) {
        val s = sideOf(m)
        if (ability(m) == Ab.MAGIC_GUARD) {
            // Still absorbs/sets Toxic Spikes, but takes no Spikes/Stealth Rock damage.
            if (grounded(m) && s.toxicSpikes > 0) {
                if (m.hasType(Types.POISON)) { s.toxicSpikes = 0; msg("${nm(m)}의 발밑에서 독압정이 사라졌다!") }
                else if (!m.hasType(Types.STEEL)) trySetStatus(m, if (s.toxicSpikes >= 2) Status.TOXIC else Status.POISON, null, silentFail = true)
            }
            return
        }
        if (s.stealthRock) {
            val q = effectiveness(Types.ROCK, null, m)
            val dmg = maxOf(1, m.maxHp * q / 32)
            msg("${nm(m)}에게 뾰족한 바위가 박혔다!")
            damage(m, dmg)
            if (m.fainted) { faint(m); return }
        }
        if (grounded(m)) {
            if (s.spikes > 0) {
                val div = when (s.spikes) { 1 -> 8; 2 -> 6; else -> 4 }
                msg("${Josa.eunNeun(nm(m))} 압정뿌리기의 데미지를 입었다!")
                damage(m, maxOf(1, m.maxHp / div))
                if (m.fainted) { faint(m); return }
            }
            if (s.toxicSpikes > 0) {
                if (m.hasType(Types.POISON)) {
                    s.toxicSpikes = 0
                    msg("${nm(m)}의 발밑에서 독압정이 사라졌다!")
                } else if (!m.hasType(Types.STEEL)) {
                    trySetStatus(m, if (s.toxicSpikes >= 2) Status.TOXIC else Status.POISON, null, silentFail = true)
                }
            }
        }
    }

    /**
     * Illusion (일루전): on entry a Zoroark disguises as the LAST non-fainted party member (name + sprite).
     * Call this right after the mon becomes active and before its send-out message so the disguise is
     * reflected everywhere. No-op if the ability isn't Illusion or there is no other healthy party member.
     */
    private fun setupIllusion(m: BattleMon) {
        m.illusionSpecies = -1; m.illusionName = null
        if (ability(m) != Ab.ILLUSION) return
        val disguise = sideOf(m).party.lastOrNull { it !== m && !it.fainted } ?: return
        if (disguise === m) return
        m.illusionSpecies = disguise.species.id
        m.illusionName = disguise.species.nameKo
    }

    /** Drops the disguise (on taking a damaging hit, or on faint) and refreshes the UI if it was up. */
    private suspend fun breakIllusion(m: BattleMon) {
        if (!m.illusionActive) return
        m.illusionSpecies = -1; m.illusionName = null
        msg("${nm(m)}의 일루전이 풀렸다!")
        emit(BattleEvent.SendOut(m.side, m))
    }

    /** Whether [m] can Mega-Evolve now: holds the matching Mega Stone and hasn't done so this battle. */
    /** Whether a mon's held item can be traded/removed by Trick/Switcheroo/Knock Off (Gen 6+). A
     *  Z-Crystal and the species' own Mega Stone are bolted on and can't be moved. */
    private fun tradeable(m: BattleMon): Boolean {
        val id = m.item
        if (id == It.NONE) return true
        if (It.isZCrystal(id)) return false
        val mega = data.megasByStone[id]
        if (mega != null && mega.species == m.species.id) return false
        return true
    }

    fun canMega(m: BattleMon): Boolean {
        if (m.megaEvolved) return false
        if (sideOf(m).usedMega) return false   // one Mega Evolution per battle per trainer
        val mega = data.megasByStone[m.item] ?: return false
        return mega.species == m.species.id
    }

    /** Applies the Mega forme: new types, recomputed stats (HP unchanged), new ability, display name. */
    private suspend fun megaEvolve(m: BattleMon) {
        if (!canMega(m)) return
        val mega = data.megasByStone[m.item] ?: return
        val prefix = if (m.side == 1) "상대 " else ""
        sideOf(m).usedMega = true
        m.megaEvolved = true
        m.megaName = mega.nameKo
        if (mega.sprite > 0) m.megaSprite = mega.sprite
        m.megaTypes = mega.types
        m.types = mega.types.toMutableList()
        // Mega stats become the new baseline so the forme persists through switch-outs (resetVolatile
        // restores stats from baseStats). HP is unchanged by Mega Evolution.
        val megaStats = m.info.statsFor(mega.base).also { it[Stat.HP] = m.stats[Stat.HP] }
        megaStats.copyInto(m.baseStats)
        m.stats = megaStats.copyOf()
        m.ability = mega.ability
        m.baseAbility = mega.ability
        msg("$prefix${Josa.eunNeun(m.species.nameKo)} 메가진화했다! ${mega.nameKo}!")
        emit(BattleEvent.MegaEvolve(m.side, m))
        // The Mega's new ability activates right away (Mega Charizard Y's Drought, Mega Abomasnow's
        // Snow Warning, Mega Gyarados' Intimidate, …).
        applyEntryAbility(m)
    }

    /**
     * HP-triggered reversible formes (Darmanitan's Zen Mode, Wishiwashi's Schooling). A forme entry in
     * zen.json names the driving ability and the HP threshold/direction:
     *  - Zen Mode: flips to the Zen forme while HP <= 1/2.
     *  - Schooling: flips to the School form while HP > 1/4 (and only at level 20+); drops back to the
     *    weak Solo form once HP falls to 1/4 or below.
     * HP is unchanged by the flip. Called after any HP change (hpTriggers) and on entry.
     */
    private suspend fun checkZenMode(m: BattleMon) {
        val forme = data.zenBySpecies[m.species.id] ?: return
        if (ability(m) != forme.ability) return
        if (m.level < forme.minLevel) return
        // Compare HP*denom vs maxHp*numer to avoid integer division.
        val belowOrEqual = m.hp * forme.hpDenom <= m.maxHp * forme.hpNumer
        val shouldActivate = if (forme.whenLowHp) belowOrEqual else !belowOrEqual
        if (shouldActivate == m.zenActive) return
        if (shouldActivate) {
            m.zenActive = true
            m.zenName = forme.nameKo
            m.zenSprite = forme.sprite
            m.types = forme.types.toMutableList()
            val hp = m.stats[Stat.HP]
            m.stats = m.info.statsFor(forme.base).also { it[Stat.HP] = hp }
            msg("${Josa.eunNeun(nm(m))} ${forme.activateMsg}")
        } else {
            m.zenActive = false
            m.types = (m.megaTypes ?: m.species.types).toMutableList()
            val hp = m.stats[Stat.HP]
            m.stats = m.baseStats.copyOf().also { it[Stat.HP] = hp }
            msg("${Josa.eunNeun(nm(m))} 원래 모습으로 돌아왔다!")
        }
        emit(BattleEvent.SendOut(m.side, m))  // refresh sprite/name
    }

    private suspend fun onEntry(m: BattleMon) {
        m.turnsOnField = 0
        applyEntryAbility(m)
        checkZenMode(m)
    }

    /** Fires the active ability's "on-entry" effect (weather, Intimidate, Download, Trace, …). Also used
     *  right after Mega Evolution so the Mega's new ability (e.g. Drought) activates immediately. */
    private suspend fun applyEntryAbility(m: BattleMon) {
        val f = foe(m)
        when (ability(m)) {
            Ab.INTIMIDATE -> if (!f.fainted) {
                msg("${nm(m)}의 위협!")
                if (f.substituteHp > 0) msg("${Josa.eunNeun(nm(f))} 영향을 받지 않았다!")
                else changeStage(f, Stat.ATK, -1, m, fromAbility = true)
            }
            Ab.SAND_STREAM -> setWeather(Weather.SAND, -1, m)
            Ab.SNOW_WARNING -> setWeather(Weather.HAIL, -1, m)
            Ab.DOWNLOAD -> if (!f.fainted) {
                val def = stageMul(f.stats[Stat.DEF], f.stages[Stat.DEF])
                val spd = stageMul(f.stats[Stat.SPD], f.stages[Stat.SPD])
                msg("${nm(m)}의 다운로드!")
                changeStage(m, if (def < spd) Stat.ATK else Stat.SPA, 1, m)
            }
            Ab.TRACE -> if (!f.fainted && ability(f) !in setOf(Ab.TRACE, Ab.NONE)) {
                m.ability = ability(f)
                msg("${Josa.eunNeun(nm(m))} ${nm(f)}의 ${Josa.eulReul(abName(m.ability))} 트레이스했다!")
                if (m.ability != Ab.TRACE) applyEntryAbility(m)
            }
            Ab.PRESSURE -> msg("${Josa.eunNeun(nm(m))} 프레셔를 발휘하고 있다!")
            Ab.MOLD_BREAKER -> msg("${Josa.eunNeun(nm(m))} 틀깨기다!")
            Ab.SLOW_START -> {
                m.slowStartTurns = 0
                msg("${Josa.eunNeun(nm(m))} 컨디션이 좋아지지 않는다!")
            }
            Ab.ANTICIPATION -> if (!f.fainted && f.moves.any {
                    !it.move.isStatus && (effectiveness(it.move.type, f, m) > 4 || it.move.effect == "ONE_HIT_KO")
                }) msg("${Josa.eunNeun(nm(m))} 몸을 떨었다!")
            Ab.FOREWARN -> if (!f.fainted) {
                val best = f.moves.maxByOrNull { forewarnPower(it.move) }
                if (best != null) msg("${nm(m)}의 위험예지로 ${nm(f)}의 ${Josa.eulReul(mvName(best.move))} 꿰뚫어 보았다!")
            }
        }
    }

    private fun forewarnPower(m: MoveData): Int = when {
        m.effect == "ONE_HIT_KO" -> 150
        m.effect in setOf("COUNTER", "MIRROR_COAT", "METAL_BURST") -> 120
        m.power == 1 -> 80
        else -> m.power
    }

    private suspend fun replaceFainted() {
        for (i in 0..1) {
            val s = sides[i]
            if (s.mon.fainted) {
                if (s.alive() == 0) checkOver()
            }
        }
        // Player chooses first, then the opponent; both enter, then entry abilities by speed.
        val entered = mutableListOf<BattleMon>()
        for (i in 0..1) {
            val s = sides[i]
            if (s.mon.fainted && s.alive() > 0) {
                val idx = controllers[i].chooseReplacement(this, i)
                s.active = idx
                val m = s.mon
                m.resetVolatile()
                setupIllusion(m)
                if (i == 0) msg("가랏! ${m.displayName}!") else msg("${Josa.eunNeun(s.trainerLabel)} ${Josa.eulReul(m.displayName)} 내보냈다!")
                emit(BattleEvent.SendOut(i, m))
                entered += m
            }
        }
        for (m in entered) {
            entryHazards(m)
            if (m.fainted) {
                checkOver()
                replaceFainted()
                return
            }
        }
        if (entered.size == 2) bySpeed(entered).forEach { onEntry(it) } else entered.forEach { onEntry(it) }
    }

    /** U-turn / Baton Pass mid-turn switch. */
    private suspend fun pivot(m: BattleMon, batonPass: Boolean) {
        val s = sideOf(m)
        if (s.switchTargets().isEmpty()) return
        val idx = controllers[m.side].chooseReplacement(this, m.side)
        switchOut(m.side, idx, announce = true, batonPass = batonPass)
    }

    // ------------------------------------------------------------------ using a move
    private suspend fun cannotMove(m: BattleMon, move: MoveData): Boolean {
        if (m.status == Status.SLEEP) {
            m.sleepTurns -= if (ability(m) == Ab.EARLY_BIRD) 2 else 1
            if (m.sleepTurns <= 0) {
                m.status = Status.NONE; m.nightmare = false
                emit(BattleEvent.StatusChanged(m.side))
                msg("${Josa.eunNeun(nm(m))} 눈을 떴다!")
            } else {
                msg("${Josa.eunNeun(nm(m))} 쿨쿨 잠들어 있다.")
                // Sleep Talk and Snore are the only moves usable while asleep.
                if (move.effect != "USE_RANDOM_LEARNED_MOVE_SLEEP" && move.effect != "DAMAGE_WHILE_ASLEEP") return true
            }
        }
        if (m.status == Status.FREEZE) {
            if (move.has("DEFROST") || rng.chance(20)) {
                m.status = Status.NONE
                emit(BattleEvent.StatusChanged(m.side))
                msg("${Josa.eunNeun(nm(m))} 얼음 상태가 나았다!")
            } else {
                msg("${Josa.eunNeun(nm(m))} 얼어버려서 움직일 수 없다!")
                return true
            }
        }
        if (ability(m) == Ab.TRUANT) {
            if (m.truantLoafing) {
                m.truantLoafing = false
                msg("${Josa.eunNeun(nm(m))} 게으름을 피우고 있다!")
                return true
            }
            m.truantLoafing = true
        }
        if (m.flinch) {
            msg("${Josa.eunNeun(nm(m))} 풀이 죽어 기술을 쓸 수 없다!")
            if (ability(m) == Ab.STEADFAST) changeStage(m, Stat.SPE, 1, m)
            return true
        }
        if (m.disabledMove?.id == move.id) {
            msg("${Josa.eunNeun(nm(m))} 사슬묶기로 ${Josa.eulReul(mvName(move))} 쓸 수 없다!")
            return true
        }
        if (m.tauntTurns > 0 && move.isStatus) {
            msg("${Josa.eunNeun(nm(m))} 도발당해서 ${Josa.eulReul(mvName(move))} 쓸 수 없다!")
            return true
        }
        if (m.confusion > 0) {
            m.confusion--
            if (m.confusion == 0) {
                msg("${Josa.eunNeun(nm(m))} 혼란이 풀렸다!")
            } else {
                msg("${Josa.eunNeun(nm(m))} 혼란에 빠져 있다!")
                if (rng.chance(50)) {
                    msg("영문도 모른 채 자신을 공격했다!")
                    val atk = stageMul(m.stats[Stat.ATK], m.stages[Stat.ATK])
                    val def = stageMul(m.stats[Stat.DEF], m.stages[Stat.DEF])
                    var d = baseDamage(m.level, 40, atk, def)
                    d = d * (100 - rng.next(16)) / 100
                    emit(BattleEvent.Hit(m.side, 4))
                    damage(m, maxOf(1, d))
                    if (m.fainted) faint(m)
                    return true
                }
            }
        }
        if (m.status == Status.PARALYSIS && rng.chance(25)) {
            msg("${Josa.eunNeun(nm(m))} 몸이 저려서 움직일 수 없다!")
            return true
        }
        // Infatuation only persists while the Pokémon it fell for is still on the field.
        val crush = m.infatuatedWith
        if (crush != null) {
            if (crush.fainted || foe(m) !== crush) {
                m.infatuatedWith = null
            } else {
                msg("${Josa.eunNeun(nm(m))} ${nm(crush)}에게 헤롱헤롱하다!")
                if (rng.chance(50)) {
                    msg("${Josa.eunNeun(nm(m))} 헤롱헤롱해서 기술을 쓸 수 없었다!")
                    return true
                }
            }
        }
        return false
    }

    private fun clearMoveLocks(m: BattleMon) {
        m.lockedMove = null; m.lockedTurns = 0
        if (m.chargingMove != null) {
            m.chargingMove = null
        }
        m.semiInvulnerable = null
    }

    private suspend fun useMove(m: BattleMon, chosen: MoveData, slot: MoveSlot?, fromAction: Boolean, pursuitBoost: Boolean) {
        val continuing = m.lockedMove != null || m.chargingMove != null
        if (cannotMove(m, chosen)) {
            val wasHidden = m.semiInvulnerable != null
            clearMoveLocks(m)
            if (wasHidden) emit(BattleEvent.Hidden(m.side, false))
            if (!m.fainted) m.lastMove = m.lastMove
            return
        }
        var move = chosen
        // Destiny Bond and Grudge last until the user's next move.
        if (move.effect != "KO_MON_THAT_DEFEATED_USER") m.destinyBond = false
        if (move.effect != "REMOVE_ALL_PP_ON_DEFEAT") m.grudge = false
        // PP
        if (slot != null && !continuing) {
            val f = foe(m)
            val cost = if (!f.fainted && ability(f) == Ab.PRESSURE && !move.targetsSelf) 2 else 1
            slot.pp = maxOf(0, slot.pp - cost)
        }
        if (move.effect == "USE_RANDOM_LEARNED_MOVE_SLEEP") {
            msg("${nm(m)}의 ${mvName(move)}!")
            emit(BattleEvent.UseMove(m.side, move))
            if (m.status != Status.SLEEP) { msg("하지만 실패했다!"); recordMove(m, move); return }
            val opts = m.moves.map { it.move }.filter {
                it.effect !in setOf("USE_RANDOM_LEARNED_MOVE_SLEEP", "USE_LAST_USED_MOVE") && !it.has("CHARGE") &&
                    it.effect !in setOf("FLY", "DIG", "DIVE", "SKIP_CHARGE_TURN_IN_SUN", "CHARGE_TURN_HIGH_CRIT_FLINCH", "HIT_LAST_WHIFF_IF_HIT", "RECHARGE_AFTER")
            }
            if (opts.isEmpty()) { msg("하지만 실패했다!"); recordMove(m, move); return }
            recordMove(m, move)
            move = opts[rng.next(opts.size)]
            executeMove(m, move, pursuitBoost, calledMove = true)
            return
        }
        if (move.effect == "USE_LAST_USED_MOVE") {
            msg("${nm(m)}의 ${mvName(move)}!")
            emit(BattleEvent.UseMove(m.side, move))
            recordMove(m, move)
            val last = lastMoveUsedInBattle
            if (last == null || last.effect in setOf("USE_LAST_USED_MOVE", "PROTECT", "COUNTER", "MIRROR_COAT", "FORCE_SWITCH", "SWITCH_HELD_ITEMS", "SURVIVE_WITH_1_HP", "COPY_MOVE_FOR_BATTLE", "USE_RANDOM_LEARNED_MOVE_SLEEP", "STRUGGLE")) {
                msg("하지만 실패했다!"); return
            }
            executeMove(m, last, pursuitBoost, calledMove = true)
            return
        }
        executeMove(m, move, pursuitBoost, calledMove = false)
    }

    private fun recordMove(m: BattleMon, move: MoveData) {
        if (m.metronomeMove?.id == move.id) m.metronomeCount++ else { m.metronomeMove = move; m.metronomeCount = 0 }
        m.lastMove = move
        m.usedMoves += move.id
        if (move.id != data.struggle.id) lastMoveUsedInBattle = move
        if (It.isChoice(item(m)) && m.choiceMove == null) m.choiceMove = move
    }

    private suspend fun executeMove(m: BattleMon, move: MoveData, pursuitBoost: Boolean, calledMove: Boolean) {
        val t = foe(m)
        val e = move.effect
        val secondTurn = m.chargingMove?.id == move.id
        // ---- two-turn moves: first turn
        if (!secondTurn && e in setOf("FLY", "DIG", "DIVE", "SKIP_CHARGE_TURN_IN_SUN", "CHARGE_TURN_HIGH_CRIT_FLINCH")) {
            val skip = (e == "SKIP_CHARGE_TURN_IN_SUN" && effWeather() == Weather.SUN)
            if (!skip) {
                msg("${nm(m)}의 ${mvName(move)}!")
                when (e) {
                    "FLY" -> msg("${Josa.eunNeun(nm(m))} 하늘 높이 날아올랐다!")
                    "DIG" -> msg("${Josa.eunNeun(nm(m))} 구멍을 파서 땅속으로 숨었다!")
                    "DIVE" -> msg("${Josa.eunNeun(nm(m))} 물속으로 숨었다!")
                    "SKIP_CHARGE_TURN_IN_SUN" -> msg("${Josa.eunNeun(nm(m))} 빛을 흡수했다!")
                    else -> msg("${Josa.eunNeun(nm(m))} 빛에 휩싸였다!")
                }
                if (item(m) == It.POWER_HERB) {
                    consumeItem(m)
                    msg("${Josa.eunNeun(nm(m))} 파워풀허브로 힘이 넘쳐흐른다!")
                } else {
                    m.chargingMove = move
                    recordMove(m, move)
                    when (e) {
                        "FLY" -> m.semiInvulnerable = "FLY"
                        "DIG" -> m.semiInvulnerable = "DIG"
                        "DIVE" -> m.semiInvulnerable = "DIVE"
                    }
                    if (m.semiInvulnerable != null) emit(BattleEvent.Hidden(m.side, true))
                    return
                }
            }
        }
        if (secondTurn) {
            m.chargingMove = null
            if (m.semiInvulnerable != null) {
                m.semiInvulnerable = null
                emit(BattleEvent.Hidden(m.side, false))
            }
        }
        msg("${nm(m)}의 ${mvName(m, move)}!")
        emit(BattleEvent.UseMove(m.side, move))
        if (!secondTurn) recordMove(m, move)

        // ---- self / field moves (these don't "miss", so the motion plays as the move resolves)
        if (move.targetsSelf || move.targetsField || e in setOf("CONVERSION2", "HIT_IN_3_TURNS", "CURSE")) {
            emit(BattleEvent.Attack(m.side, move))
            selfMove(m, move)
            return
        }
        // ---- moves that need a target
        if (t.fainted) { msg("하지만 실패했다!"); return }
        // Gen 7 Psychic Terrain: a grounded target is protected from priority moves.
        val effPrio = move.priority + if (ability(m) == Ab.PRANKSTER && move.isStatus) 1 else 0
        if (terrain == Terrain.PSYCHIC && effPrio > 0 && grounded(t) && m !== t) {
            msg("${Josa.eunNeun(nm(t))} 사이코필드에 보호받고 있다!"); return
        }
        if (e == "HIT_LAST_WHIFF_IF_HIT" && m.tookDamageThisTurn) {
            msg("${Josa.eunNeun(nm(m))} 집중이 흐트러져 기술을 쓸 수 없다!")
            return
        }
        if (e == "ALWAYS_FLINCH_FIRST_TURN_ONLY" && m.turnsOnField > 0) { msg("하지만 실패했다!"); return }
        if (e == "HIT_FIRST_IF_TARGET_ATTACKING") {
            val ta = actionsThisTurn[t.side]
            val tm = if (ta is Action.Fight) actionMove(t, ta) else null
            if (t.movedThisTurn || tm == null || tm.isStatus) { msg("하지만 실패했다!"); return }
        }
        if (e == "FAIL_IF_NOT_USED_ALL_OTHER_MOVES") {
            val others = m.moves.filter { it.move.id != move.id }
            if (others.isEmpty() || others.any { it.move.id !in m.usedMoves }) { msg("하지만 실패했다!"); return }
        }
        if (e == "RECOVER_DAMAGE_SLEEP" && t.status != Status.SLEEP) {
            msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!"); return
        }
        // Snore can only be used while asleep.
        if (e == "DAMAGE_WHILE_ASLEEP" && m.status != Status.SLEEP) { msg("하지만 실패했다!"); return }
        if (e == "HALVE_DEFENSE" && sides.any { !it.mon.fainted && ability(it.mon) == Ab.DAMP }) {
            msg("${Josa.eunNeun(nm(m))} ${Josa.eulReul(mvName(move))} 쓸 수 없다!")
            return
        }
        // Protect/Detect blocks an incoming attack. We reach here only for moves that target the foe
        // (self/field moves already branched into selfMove), so any such move is blockable unless it is
        // one of the few Protect-ignoring moves (Feint, Phantom Force, Hyperspace Hole/Fury).
        if (t.protecting && !move.targetsSelf && !move.targetsField && move.key !in protectBypassMoves) {
            msg("${Josa.eunNeun(nm(t))} 공격으로부터 몸을 지켰다!")
            if (e == "CONTINUE_AND_CONFUSE_SELF") m.lockedMove = null
            crashRecoil(m, move)
            return
        }
        if (!hitsSemiInvulnerable(m, t, move)) {
            msg("${nm(t)}에게는 맞지 않았다!")
            missed(m, move)
            return
        }
        // Sound moves vs Soundproof, absorbing abilities, and type immunity for status moves
        if (move.has("SOUND") && defAbility(m, t) == Ab.SOUNDPROOF) {
            msg("${nm(t)}의 방음!"); msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!"); return
        }
        val mtype = moveType(m, move)
        if (absorbed(m, t, move, mtype)) return
        // Gen 7: Sheer Cold (절대영도) has no effect on Ice-type Pokémon, regardless of the accuracy roll.
        if (move.key == "sheer_cold" && t.hasType(Types.ICE)) {
            msg("${nm(t)}에게는 효과가 없는 것 같다…"); return
        }
        if (!accuracyCheck(m, t, move)) {
            msg("${nm(t)}에게는 맞지 않았다!")
            missed(m, move)
            return
        }
        // All miss/fail gates passed: now the attack motion actually plays.
        emit(BattleEvent.Attack(m.side, move))
        if (move.isStatus) {
            statusMove(m, t, move)
        } else {
            attack(m, t, move, mtype, pursuitBoost)
        }
    }

    private suspend fun missed(m: BattleMon, move: MoveData) {
        if (move.effect == "CONTINUE_AND_CONFUSE_SELF") { m.lockedMove = null; m.lockedTurns = 0 }
        crashRecoil(m, move)
    }

    /**
     * High Jump Kick / Jump Kick crash: if the move whiffs (miss, Protect, or a target it can't affect),
     * the user takes recoil equal to half its maximum HP. Gen 5 uses max HP, not current HP.
     */
    private suspend fun crashRecoil(m: BattleMon, move: MoveData) {
        if (move.effect != "CRASH_ON_MISS" || m.fainted) return
        msg("${Josa.eunNeun(nm(m))} 균형을 잃고 땅에 떨어졌다!")
        damage(m, maxOf(1, m.maxHp / 2))
        if (m.fainted) faint(m)
    }

    private fun hitsSemiInvulnerable(m: BattleMon, t: BattleMon, move: MoveData): Boolean {
        val s = t.semiInvulnerable ?: return true
        if (ability(m) == Ab.NO_GUARD || ability(t) == Ab.NO_GUARD) return true
        return when (s) {
            "FLY" -> move.key in setOf("thunder", "twister", "sky_uppercut", "gust")
            "DIG" -> move.key in setOf("earthquake", "fissure", "magnitude")
            "DIVE" -> move.key in setOf("surf", "whirlpool")
            else -> true
        }
    }

    /** Volt/Water Absorb, Motor Drive, Flash Fire, Dry Skin, Lightningrod, Storm Drain, Levitate etc. */
    private suspend fun absorbed(m: BattleMon, t: BattleMon, move: MoveData, type: Int): Boolean {
        val a = defAbility(m, t)
        val targetsFoe = !move.targetsSelf && !move.targetsField
        if (!targetsFoe) return false
        when {
            type == Types.ELECTRIC && (a == Ab.VOLT_ABSORB) -> { abilityHeal(t, a); return true }
            type == Types.WATER && (a == Ab.WATER_ABSORB || a == Ab.DRY_SKIN) -> { abilityHeal(t, a); return true }
            type == Types.ELECTRIC && a == Ab.MOTOR_DRIVE -> {
                msg("${nm(t)}의 ${abName(a)}!"); changeStage(t, Stat.SPE, 1, t); return true
            }
            type == Types.ELECTRIC && a == Ab.LIGHTNINGROD -> {
                msg("${nm(t)}의 ${abName(a)}!"); changeStage(t, Stat.SPA, 1, t); return true
            }
            type == Types.WATER && a == Ab.STORM_DRAIN -> {
                msg("${nm(t)}의 ${abName(a)}!"); changeStage(t, Stat.SPA, 1, t); return true
            }
            type == Types.GRASS && a == Ab.SAP_SIPPER && !move.isStatus -> {
                msg("${nm(t)}의 초식!"); changeStage(t, Stat.ATK, 1, t); return true
            }
            type == Types.FIRE && a == Ab.FLASH_FIRE && t.status != Status.FREEZE && (move.isStatus.not() || move.key == "will_o_wisp") -> {
                msg("${nm(t)}의 ${abName(a)}!")
                if (!t.flashFire) { t.flashFire = true; msg("${nm(t)}의 불꽃의 위력이 올라갔다!") }
                else msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!")
                return true
            }
        }
        return false
    }

    private suspend fun abilityHeal(t: BattleMon, a: Int) {
        msg("${nm(t)}의 ${abName(a)}!")
        if (t.hp >= t.maxHp) msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!")
        else { heal(t, t.maxHp / 4); msg("${Josa.eunNeun(nm(t))} 체력을 회복했다!") }
    }

    private fun accuracyCheck(m: BattleMon, t: BattleMon, move: MoveData): Boolean {
        // Z-moves never miss (even when built on an inaccurate base move like Hydro Pump / Focus Blast).
        if (m.zMoveActive) return true
        if (ability(m) == Ab.NO_GUARD || ability(t) == Ab.NO_GUARD) return true
        val w = effWeather()
        if (move.effect == "ONE_HIT_KO") {
            if (t.level > m.level) return false
            return rng.next(100) < move.accuracy + (m.level - t.level)
        }
        if (move.effect == "THUNDER" && w == Weather.RAIN) return true
        if (move.effect == "BLIZZARD" && w == Weather.HAIL) return true
        if (move.accuracy > 100) return true
        if (move.isStatus && move.accuracy == 0) return true
        var acc = move.accuracy
        if (move.effect == "THUNDER" && w == Weather.SUN) acc = 50
        val evaStage = if (t.foresighted) minOf(0, t.stages[Stat.EVA]) else t.stages[Stat.EVA]
        val stage = (m.stages[Stat.ACC] - evaStage).coerceIn(-6, 6)
        val num = if (stage > 0) 3 + stage else 3
        val den = if (stage < 0) 3 - stage else 3
        var r = 0x1000
        if (ability(m) == Ab.HUSTLE && move.isPhysical) r = chain(r, 0xCCD)
        val da = defAbility(m, t)
        if (da == Ab.SAND_VEIL && w == Weather.SAND) r = chain(r, 0xCCD)
        if (da == Ab.SNOW_CLOAK && w == Weather.HAIL) r = chain(r, 0xCCD)
        when (item(m)) {
            It.WIDE_LENS -> r = chain(r, 0x1199)
            It.ZOOM_LENS -> if (t.movedThisTurn) r = chain(r, 0x1333)
        }
        when (item(t)) { It.BRIGHT_POWDER, It.LAX_INCENSE -> r = chain(r, 0xE66) }
        var chance = acc * num / den
        chance = fixedRound(chance, r).coerceAtMost(100)
        return rng.next(100) < chance
    }

    // ------------------------------------------------------------------ damage
    private fun baseDamage(level: Int, power: Int, atk: Int, def: Int): Int =
        ((2 * level / 5 + 2).toLong() * power * atk / maxOf(1, def) / 50 + 2).toInt()

    /** Internal (visible to tests): effective base power of [move] in [m]'s attack on [t]. */
    /** Gen 7 Z-move power from a move's base power. */
    private fun zPower(base: Int): Int = when {
        base <= 55 -> 100
        base <= 65 -> 120
        base <= 75 -> 140
        base <= 85 -> 160
        base <= 95 -> 175
        base <= 100 -> 180
        base <= 110 -> 185
        base <= 125 -> 190
        base <= 130 -> 195
        else -> 200
    }

    internal fun movePower(m: BattleMon, t: BattleMon, move: MoveData, type: Int, pursuitBoost: Boolean): Int {
        var p = move.power
        // Z-move: replace the move's power with its Z-power (Gen 7 base-power -> Z-power table) and skip
        // the normal variable-power rules and item/ability power multipliers. STAB/type effectiveness
        // are still applied later in calcDamage.
        if (m.zMoveActive) return zPower(move.power)
        when (move.effect) {
            "INCREASE_POWER_WITH_LESS_HP" -> {
                val x = 48 * m.hp / m.maxHp
                p = when { x < 2 -> 200; x < 5 -> 150; x < 10 -> 100; x < 17 -> 80; x < 33 -> 40; else -> 20 }
            }
            "INCREASE_POWER_WITH_MORE_HP" -> p = maxOf(1, 120 * t.hp / t.maxHp)
            "INCREASE_POWER_WITH_MORE_STAT_UP" -> p = minOf(200, 60 + 20 * (1..7).sumOf { maxOf(0, t.stages[it]) })
            "INCREASE_POWER_WITH_WEIGHT" -> p = t.species.weight.let { w ->
                when { w < 100 -> 20; w < 250 -> 40; w < 500 -> 60; w < 1000 -> 80; w < 2000 -> 100; else -> 120 }
            }
            // Heavy Slam / Heat Crash: power rises as the user outweighs the target.
            "HEAVY_SLAM" -> {
                val ratio = m.species.weight * 100 / maxOf(1, t.species.weight) // user% of target
                p = when { ratio >= 500 -> 120; ratio >= 400 -> 100; ratio >= 300 -> 80; ratio >= 200 -> 60; else -> 40 }
            }
            // Electro Ball: power rises as the user outspeeds the target.
            "ELECTRO_BALL" -> {
                val ratio = speed(m) * 100 / maxOf(1, speed(t)) // user% of target speed
                p = when { ratio >= 400 -> 150; ratio >= 300 -> 120; ratio >= 200 -> 80; ratio >= 100 -> 60; else -> 40 }
            }
            // Magnitude (carries the PSYWAVE effect in data): a random-tier Ground attack.
            "PSYWAVE" -> {
                val r = rng.next(100)
                p = when { r < 5 -> 10; r < 15 -> 30; r < 35 -> 50; r < 65 -> 70; r < 85 -> 90; r < 95 -> 110; else -> 150 }
            }
            // Hidden Power: fixed 60 power in Gen 5 (type derives from IVs, see moveType).
            "RANDOM_POWER_BASED_ON_IVS" -> p = 60
            // Natural Gift: power from the held berry (type set in moveType). Fails with no berry.
            "NATURAL_GIFT" -> p = naturalGift(item(m))?.second ?: 1
            "POWER_BASED_ON_LOW_SPEED" -> p = minOf(150, 25 * speed(t) / maxOf(1, speed(m)) + 1)
            "POWER_BASED_ON_FRIENDSHIP" -> p = maxOf(1, m.friendship * 10 / 25)
            "POWER_BASED_ON_LOW_FRIENDSHIP" -> p = maxOf(1, (255 - m.friendship) * 10 / 25)
            "HIGHER_POWER_WHEN_LOW_PP" -> {
                val left = m.moves.firstOrNull { it.move.id == move.id }?.pp ?: 0
                p = when (left) { 0 -> 200; 1 -> 80; 2 -> 60; 3 -> 50; else -> 40 }
            }
            "DOUBLE_POWER_IF_MOVING_SECOND" -> if (t.movedThisTurn) p *= 2
            "DOUBLE_POWER_IF_HIT" -> if (m.tookDamageThisTurn) p *= 2
            "DOUBLE_POWER_WHEN_BELOW_HALF" -> if (t.hp * 2 <= t.maxHp) p *= 2
            "DOUBLE_POWER_WHEN_STATUSED" -> if (m.status != Status.NONE) p *= 2
            "DOUBLE_POWER_IF_TARGET_STATUSED" -> if (t.status != Status.NONE) p *= 2
            "DOUBLE_IF_TARGET_PARALYZED" -> if (t.status == Status.PARALYSIS) p *= 2
            "DOUBLE_IF_TARGET_ASLEEP" -> if (t.status == Status.SLEEP) p *= 2
            // Eruption / Water Spout: power scales with the user's current HP (max 150, min 1).
            "POWER_BY_USER_HP" -> p = maxOf(1, p * m.hp / m.maxHp)
            // Stored Power / Power Trip: 20 + 20 per positive stat stage the USER has.
            "POWER_BY_USER_STAT_UP" -> p = minOf(860, 20 + 20 * (1..7).sumOf { maxOf(0, m.stages[it]) })
            "CHANGE_TYPE_WITH_WEATHER" -> if (effWeather() != Weather.NONE) p *= 2
            "SPIT_UP" -> p = 100 * m.stockpile
            "FLING" -> p = data.items[item(m)]?.flingPower ?: 0
            "HIT_BEFORE_SWITCH" -> if (pursuitBoost) p *= 2
            "DOUBLE_DAMAGE_DIG" -> if (t.semiInvulnerable == "DIG") p *= 2
            "DOUBLE_DAMAGE_DIVE" -> if (t.semiInvulnerable == "DIVE") p *= 2
            "FLINCH_DOUBLE_DAMAGE_FLY_OR_BOUNCE" -> if (t.semiInvulnerable == "FLY") p *= 2
            "SKIP_CHARGE_TURN_IN_SUN" -> if (effWeather() in setOf(Weather.RAIN, Weather.SAND, Weather.HAIL)) p /= 2
        }
        var r = 0x1000
        val a = ability(m)
        // "-ate"/skin abilities: a move that was Normal-type gets a 1.2x boost (the type change itself
        // is applied in moveType). 0x1333/0x1000 ≈ 1.2.
        if (move.type == Types.NORMAL && !move.isStatus && Ab.ATE_TYPE.containsKey(a)) r = chain(r, 0x1333)
        if (a == Ab.TECHNICIAN && p <= 60) r = chain(r, 0x1800)
        if (a == Ab.RIVALRY && m.gender != 2 && t.gender != 2) r = chain(r, if (m.gender == t.gender) 0x1400 else 0xC00)
        if (a == Ab.IRON_FIST && move.has("PUNCH")) r = chain(r, 0x1333)
        // Reckless boosts recoil moves 1.2x. Recoil is stored as a negative drain%, with the legacy
        // RECOIL_* effect strings also covered. (Crash moves like High Jump Kick aren't counted here.)
        if (a == Ab.RECKLESS && (move.drain < 0 || move.effect.startsWith("RECOIL"))) r = chain(r, 0x1333)
        if (a == Ab.SHEER_FORCE && hasSecondary(move)) r = chain(r, 0x14CC)
        if (a == Ab.SAND_FORCE && effWeather() == Weather.SAND && type in setOf(Types.ROCK, Types.GROUND, Types.STEEL)) r = chain(r, 0x14CC)
        if (a == Ab.DEFEATIST && m.hp * 2 <= m.maxHp) r = chain(r, 0x800)
        val da = defAbility(m, t)
        if (da == Ab.HEATPROOF && type == Types.FIRE) r = chain(r, 0x800)
        if (da == Ab.DRY_SKIN && type == Types.FIRE) r = chain(r, 0x1400)
        val it = item(m)
        if (it == It.MUSCLE_BAND && move.isPhysical) r = chain(r, 0x1199)
        if (it == It.WISE_GLASSES && move.isSpecial) r = chain(r, 0x1199)
        if (It.TYPE_BOOST[it] == type) r = chain(r, 0x1333)
        if (It.GEMS[it] == type) { r = chain(r, 0x1800); m.gemConsumedType = type }
        if (m.charged && type == Types.ELECTRIC) r = chain(r, 0x2000)
        // Gen 7 terrain: ×1.5 for the matching type from a grounded user; Grassy halves ground-quake moves.
        if (grounded(m)) when (terrain) {
            Terrain.ELECTRIC -> if (type == Types.ELECTRIC) r = chain(r, 0x1800)
            Terrain.GRASSY -> if (type == Types.GRASS) r = chain(r, 0x1800)
            Terrain.PSYCHIC -> if (type == Types.PSYCHIC) r = chain(r, 0x1800)
            else -> Unit
        }
        if (terrain == Terrain.GRASSY && move.key in setOf("earthquake", "magnitude", "bulldoze") && grounded(t)) r = chain(r, 0x800)
        if (terrain == Terrain.MISTY && type == Types.DRAGON && grounded(t)) r = chain(r, 0x800)
        return maxOf(1, fixedRound(p, r))
    }

    /** A move Sheer Force boosts (it has a chance-based secondary) and whose secondary is then skipped. */
    private fun hasSecondary(m: MoveData): Boolean {
        if (m.isStatus) return false
        if (m.flinch > 0) return true
        if (m.inflict != "NONE" && m.inflictChance > 0) return true
        if (m.stats.any { it.chance > 0 }) return true
        return m.effect in setOf("RAISE_ALL_STATS_HIT", "RAISE_ATTACK_HIT", "RAISE_DEF_HIT", "RAISE_SP_ATK_HIT")
    }

    private class DamageResult(val damage: Int, val crit: Boolean, val eff: Int)

    private fun calcDamage(m: BattleMon, t: BattleMon, move: MoveData, type: Int, pursuitBoost: Boolean, forceCrit: Boolean? = null, roll: Int? = null): DamageResult {
        val eff = effectiveness(type, m, t)
        if (eff == 0) return DamageResult(0, false, 0)
        // Gen 7 crits: stage ladder 1 in {24, 8, 2, 1}; stage 3+ is a guaranteed crit.
        val critBlocked = defAbility(m, t) in setOf(Ab.BATTLE_ARMOR, Ab.SHELL_ARMOR)
        var stage = move.crit
        if (ability(m) == Ab.SUPER_LUCK) stage++
        if (m.focusEnergy) stage += 2
        if (item(m) in setOf(It.SCOPE_LENS, It.RAZOR_CLAW)) stage++
        val critDen = intArrayOf(24, 8, 2, 1)[stage.coerceIn(0, 3)]
        val crit = forceCrit ?: (!critBlocked && (critDen == 1 || rng.next(critDen) == 0))
        val power = movePower(m, t, move, type, pursuitBoost)
        // attack
        val physical = move.isPhysical
        val ai = if (physical) Stat.ATK else Stat.SPA
        val di = if (physical) Stat.DEF else Stat.SPD
        // Foul Play (속임수): compute damage from the TARGET's Attack stat (and the target's Atk stage),
        // not the user's. The user's own attack-boosting ability/item then do not apply.
        val foulPlay = move.effect == "FOUL_PLAY"
        val atkMon = if (foulPlay) t else m
        var aStage = atkMon.stages[ai]
        var dStage = t.stages[di]
        if (crit) { aStage = maxOf(0, aStage); dStage = minOf(0, dStage) }
        // Unaware ignores the other side's relevant stat stages.
        if (ability(t) == Ab.UNAWARE && !foulPlay) aStage = 0
        if (ability(m) == Ab.UNAWARE) dStage = 0
        var atk = stageMul(atkMon.stats[ai], aStage)
        var def = stageMul(t.stats[di], dStage)
        val a = ability(m)
        val it = item(m)
        var ar = 0x1000
        if (!foulPlay) {
            if (physical && a == Ab.PURE_POWER) atk *= 2
            if (physical && a == Ab.HUSTLE) atk = atk * 3 / 2
            if (physical && a == Ab.GUTS && m.status != Status.NONE) ar = chain(ar, 0x1800)
            if (a == Ab.SLOW_START && physical && m.slowStartTurns < 5) ar = chain(ar, 0x800)
            if (m.flashFire && type == Types.FIRE) ar = chain(ar, 0x1800)
            if (m.hp * 3 <= m.maxHp) {
                if ((a == Ab.OVERGROW && type == Types.GRASS) || (a == Ab.BLAZE && type == Types.FIRE) ||
                    (a == Ab.TORRENT && type == Types.WATER) || (a == Ab.SWARM && type == Types.BUG)) ar = chain(ar, 0x1800)
            }
            if (it == It.CHOICE_BAND && physical) ar = chain(ar, 0x1800)
            if (it == It.CHOICE_SPECS && !physical) ar = chain(ar, 0x1800)
            if (it == It.THICK_CLUB && physical && m.species.id in setOf(104, 105)) ar = chain(ar, 0x2000)
        }
        val da = defAbility(m, t)
        if (da == Ab.THICK_FAT && (type == Types.FIRE || type == Types.ICE)) ar = chain(ar, 0x800)
        atk = maxOf(1, fixedRound(atk, ar))
        var dr = 0x1000
        if (physical && da == Ab.MARVEL_SCALE && t.status != Status.NONE) dr = chain(dr, 0x1800)
        if (!physical && effWeather() == Weather.SAND && t.hasType(Types.ROCK)) dr = chain(dr, 0x1800)
        def = maxOf(1, fixedRound(def, dr))

        var d = baseDamage(m.level, power, atk, def)
        // weather
        val w = effWeather()
        if (w == Weather.SUN) { if (type == Types.FIRE) d = d * 3 / 2 else if (type == Types.WATER) d /= 2 }
        if (w == Weather.RAIN) { if (type == Types.WATER) d = d * 3 / 2 else if (type == Types.FIRE) d /= 2 }
        if (crit) d = d * 3 / 2  // Gen 6+ critical hit multiplier (1.5x)
        d = d * (roll ?: (100 - rng.next(16))) / 100
        if (type != Types.NULL && m.hasType(type)) d = fixedRound(d, if (a == Ab.ADAPTABILITY) 0x2000 else 0x1800)
        d = d * eff / 4
        if (physical && m.status == Status.BURN && a != Ab.GUTS) d /= 2
        d = maxOf(1, d)
        // final modifiers
        var fr = 0x1000
        val side = sideOf(t)
        if (!crit && ((physical && side.reflect > 0) || (!physical && side.lightScreen > 0))) fr = chain(fr, 0x800)
        if (a == Ab.TINTED_LENS && eff < 4) fr = chain(fr, 0x2000)
        if (a == Ab.SNIPER && crit) fr = chain(fr, 0x1800)
        if ((da == Ab.FILTER || da == Ab.SOLID_ROCK) && eff > 4) fr = chain(fr, 0xC00)
        if (it == It.EXPERT_BELT && eff > 4) fr = chain(fr, 0x1333)
        if (it == It.LIFE_ORB) fr = chain(fr, 0x14CC)
        if (it == It.METRONOME && m.metronomeMove?.id == move.id && m.metronomeCount > 0) {
            fr = chain(fr, 0x1000 + minOf(m.metronomeCount, 5) * 0x333)
        }
        d = maxOf(1, fixedRound(d, fr))
        return DamageResult(d, crit, eff)
    }

    /** Average-roll damage estimate (no crit) for the AI. */
    fun estimateDamage(m: BattleMon, t: BattleMon, move: MoveData): Int {
        if (move.isStatus) return 0
        val type = moveType(m, move)
        if (move.effect == "ONE_HIT_KO") return if (effectiveness(type, m, t) == 0 || defAbility(m, t) == Ab.STURDY ||
            (move.key == "sheer_cold" && t.hasType(Types.ICE))) 0 else t.hp * 30 / 100
        if (move.effect == "LEVEL_DAMAGE_FLAT") return if (effectiveness(type, m, t) == 0) 0 else m.level
        if (move.effect == "RANDOM_DAMAGE_1_TO_150_LEVEL") return if (effectiveness(type, m, t) == 0) 0 else m.level
        if (move.effect == "HALVE_HP") return t.hp / 2
        if (move.effect == "SET_HP_EQUAL_TO_USER") return maxOf(0, t.hp - m.hp)
        if (move.effect == "FINAL_GAMBIT") return if (effectiveness(type, m, t) == 0) 0 else m.hp
        if (move.effect == "NATURAL_GIFT" && naturalGift(item(m)) == null) return 0
        if (move.effect in setOf("COUNTER", "MIRROR_COAT", "METAL_BURST")) return 0
        val tType = type
        val ab = defAbility(m, t)
        if ((tType == Types.ELECTRIC && ab in setOf(Ab.VOLT_ABSORB, Ab.MOTOR_DRIVE, Ab.LIGHTNINGROD)) ||
            (tType == Types.WATER && ab in setOf(Ab.WATER_ABSORB, Ab.DRY_SKIN, Ab.STORM_DRAIN)) ||
            (tType == Types.FIRE && ab == Ab.FLASH_FIRE) || (move.has("SOUND") && ab == Ab.SOUNDPROOF)
        ) return 0
        val r = calcDamage(m, t, move, type, false, forceCrit = false, roll = 92)
        val hits = when {
            move.effect == "HIT_TWICE" -> 2
            move.effect == "MULTI_HIT" -> if (ability(m) == Ab.TECHNICIAN) 3 else 3
            else -> 1
        }
        return r.damage * hits
    }

    private suspend fun effMessage(eff: Int, t: BattleMon) {
        // eff is in quarters: 16 = x4, 8 = x2, 2 = x1/2, 1 = x1/4.
        when {
            eff >= 16 -> msg("효과가 매우 굉장했다!")
            eff > 4 -> msg("효과가 굉장했다!")
            eff <= 1 -> msg("효과가 매우 별로였다…")
            eff in 2..3 -> msg("효과가 별로인 듯하다…")
        }
    }

    private suspend fun attack(m: BattleMon, t: BattleMon, move: MoveData, type: Int, pursuitBoost: Boolean) {
        val e = move.effect
        // Explosion / Self-Destruct are prevented entirely (user does NOT faint) by Damp.
        if (e == "USER_FAINTS" && defAbility(m, t) == Ab.DAMP) {
            msg("${nm(t)}의 ${abName(Ab.DAMP)}로 ${Josa.eulReul(mvName(move))} 쓸 수 없다!"); return
        }
        // ---- damage that ignores the formula
        val fixed: Int? = when (e) {
            "COUNTER" -> if (m.lastHitBy === t && m.lastDamagePhysical > 0) m.lastDamagePhysical * 2 else -1
            "MIRROR_COAT" -> if (m.lastHitBy === t && m.lastDamageSpecial > 0) m.lastDamageSpecial * 2 else -1
            "METAL_BURST" -> if (m.lastHitBy === t && (m.lastDamagePhysical + m.lastDamageSpecial) > 0) (m.lastDamagePhysical + m.lastDamageSpecial) * 3 / 2 else -1
            "SET_HP_EQUAL_TO_USER" -> if (t.hp > m.hp) t.hp - m.hp else -1
            "ONE_HIT_KO" -> t.hp
            // Night Shade / Seismic Toss: fixed damage equal to the user's level.
            "LEVEL_DAMAGE_FLAT" -> m.level
            // Psywave: 0.5x..1.5x the user's level, random.
            "RANDOM_DAMAGE_1_TO_150_LEVEL" -> maxOf(1, m.level * (50 + rng.next(101)) / 100)
            // Super Fang: halve the target's current HP.
            "HALVE_HP" -> maxOf(1, t.hp / 2)
            // Final Gambit: deal damage equal to the user's current HP, then the user faints.
            "FINAL_GAMBIT" -> m.hp
            else -> null
        }
        if ((e == "SPIT_UP" && m.stockpile == 0) ||
            (e == "FLING" && (item(m) == It.NONE || (data.items[item(m)]?.flingPower ?: 0) == 0)) ||
            (e == "NATURAL_GIFT" && naturalGift(item(m)) == null)
        ) {
            msg("하지만 실패했다!"); return
        }
        if (fixed != null) {
            if (fixed < 0) { msg("하지만 실패했다!"); return }
            val eff = effectiveness(type, m, t)
            if (eff == 0) { msg("${nm(t)}에게는 효과가 없는 것 같다…"); return }
            if (e == "ONE_HIT_KO" && defAbility(m, t) == Ab.STURDY) {
                msg("${nm(t)}의 옹골참!"); msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!"); return
            }
            dealDamage(m, t, move, minOf(fixed, if (t.substituteHp > 0) t.substituteHp else t.hp), 4, false, showEff = false)
            if (e == "ONE_HIT_KO" && t.fainted) msg("일격필살!")
            afterHit(m, t, move, type, listOf(fixed))
            // Final Gambit: the user faints after dealing damage.
            if (e == "FINAL_GAMBIT" && !m.fainted) { damage(m, m.hp); faint(m) }
            return
        }
        val eff0 = effectiveness(type, m, t)
        if (eff0 == 0) {
            msg("${nm(t)}에게는 효과가 없는 것 같다…")
            if (e == "CONTINUE_AND_CONFUSE_SELF") m.lockedMove = null
            // Jump Kick / High Jump Kick crashes when it hits a target it can't affect (e.g. a Ghost).
            crashRecoil(m, move)
            // Explosion / Self-Destruct still makes the user faint even against an immune target.
            if (e == "USER_FAINTS" && !m.fainted) { damage(m, m.hp); faint(m) }
            return
        }
        // Multi-hit count. Driven by the move's hitsMin/hitsMax data (effect strings are kept as a
        // fallback). A 2-5 hit move rolls 2(35%)/3(35%)/4(15%)/5(15%); Skill Link always hits the max.
        val hits = when {
            e == "HIT_TWICE" -> 2
            e == "MULTI_HIT" || (move.hitsMax > move.hitsMin && move.hitsMax > 1) -> {
                val hi = move.hitsMax.coerceAtMost(5)
                if (ability(m) == Ab.SKILL_LINK) hi // Skill Link always hits the maximum number of times
                else {
                    val lo = move.hitsMin.coerceAtLeast(2)
                    if (hi <= lo) lo else {
                        val r = rng.next(100)
                        when { r < 35 -> 2; r < 70 -> 3; r < 85 -> 4; else -> 5 }.coerceIn(lo, hi)
                    }
                }
            }
            move.hitsMin > 1 && move.hitsMin == move.hitsMax -> move.hitsMin // fixed 2-hit (Double Kick, Dual Chop, …)
            else -> 1
        }
        val dealt = mutableListOf<Int>()
        var lastEff = eff0
        var anyCrit = false
        for (i in 0 until hits) {
            if (t.fainted || m.fainted) break
            val r = calcDamage(m, t, move, type, pursuitBoost)
            var d = r.damage
            // resist berries
            val berryType = It.RESIST_BERRIES[item(t)]
            if (berryType != null && berryType == type && r.eff > 4 && t.substituteHp == 0) {
                consumeItem(t)
                msg("${Josa.eunNeun(nm(t))} ${itName(t.lostItem)}로 위력이 약해졌다!")
                d = maxOf(1, d / 2)
            }
            if (r.crit) anyCrit = true
            lastEff = r.eff
            val done = dealDamage(m, t, move, d, r.eff, r.crit, showEff = hits == 1)
            dealt += done
        }
        if (hits > 1) {
            effMessage(lastEff, t)
            msg("${dealt.size}번 맞았다!")
        }
        if (anyCrit && !t.fainted && defAbility(m, t) == Ab.ANGER_POINT && t.substituteHp == 0 && dealt.isNotEmpty()) {
            msg("${nm(t)}의 분노의경혈!")
            t.stages[Stat.ATK] = 6
            msg("${nm(t)}의 공격이 최대로 올라갔다!")
        }
        afterHit(m, t, move, type, dealt)
    }

    /** Applies damage to the substitute or the target; handles Endure/Focus Sash/Sturdy/Focus Band. Returns HP lost. */
    private suspend fun dealDamage(m: BattleMon, t: BattleMon, move: MoveData, dmg: Int, eff: Int, crit: Boolean, showEff: Boolean): Int {
        if (t.substituteHp > 0 && !move.has("BYPASS_SUBSTITUTE") && m !== t) {
            val d = minOf(dmg, t.substituteHp)
            t.substituteHp -= d
            emit(BattleEvent.Hit(t.side, eff))
            if (crit) msg("급소에 맞았다!")
            if (showEff) effMessage(eff, t)
            msg("대신 ${nm(t)}의 분신이 공격을 받았다!")
            if (t.substituteHp <= 0) {
                t.substituteHp = 0
                emit(BattleEvent.Substitute(t.side, false))
                msg("${nm(t)}의 분신은 사라져 버렸다…")
            }
            return 0
        }
        // Mimikyu's Disguise: the first damaging hit is nullified; it busts and loses 1/8 of max HP (Gen 7).
        if (defAbility(m, t) == Ab.DISGUISE && !t.busted && m !== t && !move.isStatus) {
            emit(BattleEvent.Hit(t.side, eff))
            if (crit) msg("급소에 맞았다!")
            msg("${nm(t)}의 탈이 공격을 막아냈다!")
            t.busted = true
            damage(t, maxOf(1, t.maxHp / 8))
            msg("${Josa.eunNeun(nm(t))} 탈이 벗겨졌다!")
            emit(BattleEvent.SendOut(t.side, t))  // refresh sprite to Busted form
            t.lastHitBy = m
            if (!t.fainted) hpTriggers(t)
            return 0
        }
        var d = minOf(dmg, t.hp)
        var survived: String? = null
        if (d >= t.hp) {
            when {
                t.enduring -> { d = t.hp - 1; survived = "ENDURE" }
                defAbility(m, t) == Ab.STURDY && t.hp == t.maxHp -> { d = t.hp - 1; survived = "STURDY" }
                item(t) == It.FOCUS_SASH && t.hp == t.maxHp -> { d = t.hp - 1; survived = "SASH" }
                item(t) == It.FOCUS_BAND && rng.chance(10) -> { d = t.hp - 1; survived = "BAND" }
            }
        }
        emit(BattleEvent.Hit(t.side, eff))
        damage(t, d)
        if (crit) msg("급소에 맞았다!")
        if (showEff) effMessage(eff, t)
        when (survived) {
            "ENDURE" -> msg("${Josa.eunNeun(nm(t))} 공격을 버텼다!")
            "STURDY" -> msg("${Josa.eunNeun(nm(t))} 옹골참으로 버텼다!")
            "SASH" -> { consumeItem(t); msg("${Josa.eunNeun(nm(t))} 기합의띠로 버텼다!") }
            "BAND" -> msg("${Josa.eunNeun(nm(t))} 기합의머리띠로 버텼다!")
        }
        t.tookDamageThisTurn = true
        t.lastHitBy = m
        t.lastHitMoveType = moveType(m, move)
        if (move.isPhysical) { t.lastDamagePhysical = d; t.lastDamageSpecial = 0 } else { t.lastDamageSpecial = d; t.lastDamagePhysical = 0 }
        if (t.status == Status.FREEZE && moveType(m, move) == Types.FIRE && !t.fainted) {
            t.status = Status.NONE; emit(BattleEvent.StatusChanged(t.side)); msg("${Josa.eunNeun(nm(t))} 얼음 상태가 나았다!")
        }
        // Illusion drops the moment the disguised mon takes a direct hit from a damaging move.
        if (t.illusionActive) breakIllusion(t)
        if (!t.fainted) hpTriggers(t)
        return d
    }

    private suspend fun afterHit(m: BattleMon, t: BattleMon, move: MoveData, type: Int, dealt: List<Int>) {
        val e = move.effect
        val total = dealt.sum()
        val hitTarget = dealt.isNotEmpty()
        val subHit = total == 0 && hitTarget
        val contact = move.has("CONTACT")
        // Natural Gift uses up the held berry.
        if (e == "NATURAL_GIFT" && naturalGift(item(m)) != null && !m.fainted) consumeItem(m)
        // drain
        if (move.drain > 0 && total > 0) {
            var h = maxOf(1, total * move.drain / 100)
            if (item(m) == It.BIG_ROOT) h = h * 13 / 10
            if (ability(t) == Ab.LIQUID_OOZE) {
                msg("${Josa.eunNeun(nm(m))} 해감액을 흡수했다!")
                damage(m, h)
            } else if (m.hp < m.maxHp) {
                heal(m, h)
                msg(if (e == "RECOVER_DAMAGE_SLEEP") "${nm(t)}의 꿈을 먹었다!" else "${Josa.iGa(nm(t))} 체력을 흡수당했다!")
            }
        }
        // recoil. Recoil is carried in the data as a NEGATIVE `drain` percentage (e.g. Wood Hammer /
        // Double-Edge/Flare Blitz = -33 -> 1/3 of the damage dealt, Head Smash = -50, Wild Charge = -25).
        // The legacy RECOIL_* effect strings are also honoured for safety, but the data uses drain.
        val recoilPercent = when {
            move.drain < 0 -> -move.drain
            e in setOf("RECOIL_THIRD", "RECOIL_BURN_HIT", "RECOIL_PARALYZE_HIT") -> 33
            e == "RECOIL_HALF" -> 50
            else -> 0
        }
        if (recoilPercent > 0 && total > 0 && ability(m) !in setOf(Ab.ROCK_HEAD, Ab.MAGIC_GUARD) && !m.fainted) {
            msg("${Josa.eunNeun(nm(m))} 공격의 반동으로 데미지를 입었다!")
            damage(m, maxOf(1, total * recoilPercent / 100))
        }
        if (e == "STRUGGLE" && !m.fainted && ability(m) != Ab.MAGIC_GUARD) {
            msg("${Josa.eunNeun(nm(m))} 공격의 반동으로 데미지를 입었다!")
            damage(m, maxOf(1, m.maxHp / 4))
        }
        // consume a used gem
        if (m.gemConsumedType == type && item(m) in It.GEMS && !m.fainted) {
            msg("${nm(m)}은 ${itName(m.item)}로 위력을 높였다!")
            consumeItem(m)
        }
        m.gemConsumedType = -1
        // contact abilities of the target
        if (contact && hitTarget && !subHit && !m.fainted) contactAbility(m, t)
        // Poison Touch: contact may poison the target
        if (contact && hitTarget && !subHit && !t.fainted && ability(m) == Ab.POISON_TOUCH && rng.chance(30))
            trySetStatus(t, Status.POISON, m, silentFail = true)
        // Rocky Helmet: contact attacker loses 1/6 (unless Magic Guard)
        if (contact && hitTarget && !subHit && !m.fainted && item(t) == It.ROCKY_HELMET && ability(m) != Ab.MAGIC_GUARD) {
            msg("${nm(m)}은 울퉁불퉁멧으로 데미지를 입었다!")
            damage(m, maxOf(1, m.maxHp / 6))
            if (m.fainted) faint(m)
        }
        // Air Balloon pops when its holder is hit by a damaging move
        if (hitTarget && !t.fainted && item(t) == It.AIR_BALLOON) {
            consumeItem(t); msg("${nm(t)}의 풍선이 터졌다!")
        }
        // Jaboca Berry: physical hit makes the attacker lose 1/8
        if (hitTarget && !subHit && !m.fainted && move.isPhysical && item(t) == It.JABOCA_BERRY && ability(m) != Ab.MAGIC_GUARD) {
            consumeItem(t); msg("${nm(m)}은 자뭉열매로 데미지를 입었다!")
            damage(m, maxOf(1, m.maxHp / 8)); if (m.fainted) faint(m)
        }
        // Enigma Berry: heal 1/4 when hit super-effectively
        if (hitTarget && !t.fainted && item(t) == It.ENIGMA_BERRY && dealt.isNotEmpty()) {
            val wasSE = effectiveness(type, m, t) > 4
            if (wasSE) { consumeItem(t); if (t.hp < t.maxHp) { heal(t, t.maxHp / 4); msg("${nm(t)}은 비자열매로 체력을 회복했다!") } }
        }
        // secondary effects (not through a substitute)
        if (hitTarget && !subHit) secondary(m, t, move, type, total)
        // self-affecting effects that happen even through a substitute
        when (e) {
            "LOWER_OWN_ATK_AND_DEF", "DEF_SPD_DOWN_HIT", "USER_SP_ATK_DOWN_2", "SPEED_DOWN_HIT" ->
                if (!m.fainted && hitTarget) for (s in move.stats) changeStage(m, Stat.parse(s.stat), s.stages, m)
            "RECHARGE_AFTER" -> if (!m.fainted) m.recharging = true
            "HALVE_DEFENSE" -> if (!m.fainted) { m.hp = 0 ; emit(BattleEvent.Hp(m.side, 1, 0, m.maxHp)) }
            "CONTINUE_AND_CONFUSE_SELF" -> {
                if (m.lockedMove == null) { m.lockedMove = move; m.lockedTurns = 1 + rng.next(2) }
                else m.lockedTurns--
                if (m.lockedTurns <= 0) {
                    m.lockedMove = null
                    if (!m.fainted && m.confusion == 0 && ability(m) != Ab.OWN_TEMPO) {
                        m.confusion = 2 + rng.next(4)
                        msg("${Josa.eunNeun(nm(m))} 지쳐서 혼란에 빠졌다!")
                    }
                }
            }
            "REMOVE_SCREENS" -> {
                val s = sideOf(t)
                if (s.reflect > 0 || s.lightScreen > 0) { s.reflect = 0; s.lightScreen = 0; msg("벽을 부쉈다!") }
            }
            "SWITCH_HIT" -> Unit
        }
        if (e == "SPIT_UP") { m.stages[Stat.DEF] -= m.stockpileDef; m.stages[Stat.SPD] -= m.stockpileSpd; m.stockpile = 0; m.stockpileDef = 0; m.stockpileSpd = 0; msg("${nm(m)}의 비축 효과가 사라졌다!") }
        // attacker items
        val it = item(m)
        if (total > 0 && !m.fainted) {
            if (it == It.SHELL_BELL) { if (m.hp < m.maxHp) { heal(m, maxOf(1, total / 8)); msg("${Josa.eunNeun(nm(m))} 조개껍질방울로 체력을 조금 회복했다!") } }
            if (it == It.LIFE_ORB && ability(m) != Ab.MAGIC_GUARD) { damage(m, maxOf(1, m.maxHp / 10)); msg("${Josa.eunNeun(nm(m))} 생명이 조금 깎였다!") }
        }
        if (e == "FLING" && m.item != It.NONE) {
            val flung = m.item
            consumeItem(m)
            if (!t.fainted && hitTarget && !subHit) when (flung) {
                It.TOXIC_ORB -> trySetStatus(t, Status.TOXIC, m)
                It.KINGS_ROCK, It.RAZOR_FANG -> if (!t.movedThisTurn) t.flinch = true
                It.WHITE_HERB -> Unit
                else -> if (It.isBerry(flung)) eatBerry(t, flung, forced = true)
            }
        }
        // Moxie: raise Attack when the user KOs the target
        if (t.fainted && !m.fainted && ability(m) == Ab.MOXIE && m.stages[Stat.ATK] < 6) {
            msg("${nm(m)}의 자기과신!"); changeStage(m, Stat.ATK, 1, m)
        }
        // Explosion / Self-Destruct: the user faints after the move resolves. Do this BEFORE announcing
        // the target's faint, so a double-KO still faints the user even if checkOver() ends the battle.
        if (e == "USER_FAINTS" && !m.fainted) damage(m, m.hp)
        // faints
        if (t.fainted) {
            if (t.destinyBond && !m.fainted) {
                msg("${Josa.eunNeun(nm(t))} ${Josa.eulReul(nm(m))} 길동무로 삼았다!")
                damage(m, m.hp)
            }
            if (t.grudge && !m.fainted) {
                m.moves.firstOrNull { it.move.id == move.id }?.let { it.pp = 0 }
                msg("${nm(m)}의 ${Josa.iGa(mvName(move))} 원념으로 PP가 0이 되었다!")
            }
            if (m.fainted) faint(m)  // the exploding user is announced first on a double KO
            faint(t)
        }
        if (m.fainted) faint(m)
        // U-turn switches after the hit
        if (e == "SWITCH_HIT" && !m.fainted && hitTarget && sides[m.side].switchTargets().isNotEmpty() && sides[1 - m.side].alive() > 0) {
            pivot(m, batonPass = false)
        }
        // Dragon Tail / Circle Throw: after dealing damage, drag the target out for a random party member.
        // Fails silently (no switch) vs Suction Cups, an Ingrained target, a fainted target, or an empty bench.
        if (e == "CIRCLE_THROW" && hitTarget && !t.fainted && !subHit &&
            defAbility(m, t) != Ab.SUCTION_CUPS && !t.ingrained && sideOf(t).switchTargets().isNotEmpty()
        ) {
            dragOut(m, t)
        }
        // Grudge/destiny bond only last until the user's next move
    }

    private suspend fun contactAbility(m: BattleMon, t: BattleMon) {
        val a = ability(t)
        when (a) {
            Ab.STATIC -> if (rng.chance(30)) { msg("${nm(t)}의 정전기!"); trySetStatus(m, Status.PARALYSIS, t, silentFail = true) }
            Ab.FLAME_BODY -> if (rng.chance(30)) { msg("${nm(t)}의 불꽃몸!"); trySetStatus(m, Status.BURN, t, silentFail = true) }
            Ab.POISON_POINT -> if (rng.chance(30)) { msg("${nm(t)}의 독가시!"); trySetStatus(m, Status.POISON, t, silentFail = true) }
            Ab.EFFECT_SPORE -> if (rng.chance(30)) {
                msg("${nm(t)}의 포자!")
                trySetStatus(m, listOf(Status.SLEEP, Status.PARALYSIS, Status.POISON)[rng.next(3)], t, silentFail = true)
            }
            // Cute Charm: contact may infatuate the attacker toward the Cute Charm Pokémon (the defender, t).
            Ab.CUTE_CHARM -> if (rng.chance(30) && m.gender != 2 && t.gender != 2 && m.gender != t.gender && m.infatuatedWith == null && ability(m) != Ab.OBLIVIOUS) {
                msg("${nm(t)}의 헤롱헤롱바디!")
                m.infatuatedWith = t
                msg("${Josa.eunNeun(nm(m))} 헤롱헤롱해졌다!")
            }
            Ab.AFTERMATH -> if (t.fainted && !m.fainted && sides.none { ability(it.mon) == Ab.DAMP }) {
                msg("${nm(t)}의 유폭!")
                damage(m, maxOf(1, m.maxHp / 4))
                if (m.fainted) faint(m)
            }
            Ab.ROUGH_SKIN, Ab.IRON_BARBS -> if (!m.fainted && ability(m) != Ab.MAGIC_GUARD) {
                msg("${nm(t)}의 ${abName(a)}!")
                damage(m, maxOf(1, m.maxHp / 8))
                if (m.fainted) faint(m)
            }
            Ab.MUMMY -> if (!m.fainted && ability(m) != Ab.MUMMY) {
                m.ability = Ab.MUMMY; msg("${nm(m)}의 특성이 미라가 되었다!")
            }
            Ab.CURSED_BODY -> if (!m.fainted && m.disabledMove == null && rng.chance(30)) {
                m.lastMove?.let { m.disabledMove = it; m.disableTurns = 4; msg("${nm(m)}의 ${Josa.eulReul(mvName(it))} 사슬묶기 당했다!") }
            }
            Ab.WEAK_ARMOR -> if (!t.fainted) {
                changeStage(t, Stat.DEF, -1, t, fromAbility = true, failMsg = false)
                changeStage(t, Stat.SPE, 1, t, fromAbility = true, failMsg = false)
            }
        }
    }

    private suspend fun secondary(m: BattleMon, t: BattleMon, move: MoveData, type: Int, total: Int) {
        val e = move.effect
        // A Z-move is treated as a separate move: the base move's added effects (secondary status,
        // flinch, and stat changes — including self-stat drops like Overheat/Draco Meteor) do NOT fire.
        if (m.zMoveActive) return
        // Sheer Force removes the move's added effects (and already boosted its power).
        if (ability(m) == Ab.SHEER_FORCE && hasSecondary(move)) return
        val serene = if (ability(m) == Ab.SERENE_GRACE) 2 else 1
        fun roll(chance: Int) = chance >= 100 || rng.next(100) < chance * serene
        // status
        if (!t.fainted && move.inflict != "NONE" && move.inflictChance > 0) {
            if (roll(move.inflictChance)) when (move.inflict) {
                "BURN" -> trySetStatus(t, Status.BURN, m, silentFail = true)
                "FREEZE" -> trySetStatus(t, Status.FREEZE, m, silentFail = true)
                "PARALYSIS" -> trySetStatus(t, Status.PARALYSIS, m, silentFail = true)
                "POISON" -> trySetStatus(t, if (e == "BADLY_POISON_HIT") Status.TOXIC else Status.POISON, m, silentFail = true)
                "CONFUSION" -> tryConfuse(t, m, silentFail = true)
                "TRI" -> trySetStatus(t, listOf(Status.BURN, Status.FREEZE, Status.PARALYSIS)[rng.next(3)], m, silentFail = true)
                "BIND" -> if (t.bindTurns == 0) {
                    t.bindTurns = if (item(m) == It.GRIP_CLAW) 7 else 4 + rng.next(2)
                    t.bindMove = move
                    msg(when (move.key) {
                        "wrap" -> "${Josa.eunNeun(nm(t))} ${nm(m)}에게 조여졌다!"
                        "whirlpool" -> "${Josa.eunNeun(nm(t))} 소용돌이에 갇혔다!"
                        "sand_tomb" -> "${Josa.eunNeun(nm(t))} 모래지옥에 갇혔다!"
                        else -> "${Josa.eunNeun(nm(t))} 마그마의 소용돌이에 갇혔다!"
                    })
                }
            }
        }
        // flinch
        if (!t.fainted && !t.movedThisTurn) {
            var flinch = move.flinch
            if (e == "ALWAYS_FLINCH_FIRST_TURN_ONLY") flinch = 100
            if (flinch > 0 && roll(flinch) && defAbility(m, t) != Ab.INNER_FOCUS) t.flinch = true
            else if (flinch == 0 && (item(m) == It.KINGS_ROCK || item(m) == It.RAZOR_FANG || ability(m) == Ab.STENCH) &&
                rng.chance(10) && defAbility(m, t) != Ab.INNER_FOCUS) t.flinch = true
        }
        // Stat changes from a damaging move's secondary effect. `statsSelf` (from the data: PokeAPI's
        // "damage+raise" category) means they apply to the USER — Charge Beam (+SpA), Overheat (-SpA),
        // Close Combat (-Def/-SpD), Hammer Arm (-Spe), Superpower (-Atk/-Def), etc. Otherwise they lower
        // the TARGET (Crunch, Psychic, Shadow Ball, …). The legacy effect-name sets are kept as a
        // fallback for data that predates the statsSelf flag.
        val legacySelf = e in setOf(
            "RAISE_ATTACK_HIT", "RAISE_DEF_HIT", "RAISE_SP_ATK_HIT", "RAISE_ALL_STATS_HIT",
            "LOWER_OWN_ATK_AND_DEF", "DEF_SPD_DOWN_HIT", "USER_SP_ATK_DOWN_2", "SPEED_DOWN_HIT",
        )
        if (move.stats.isNotEmpty()) {
            val chance = move.stats[0].chance
            if (roll(chance)) {
                val toSelf = move.statsSelf || legacySelf
                val target = if (toSelf) m else t
                // A self-targeted secondary still applies even if the target fainted; a target-targeted
                // one is skipped when the target is down.
                if (toSelf || !target.fainted) for (s in move.stats) {
                    if (s.stat == "ALL") for (st in Stat.ATK..Stat.SPD) changeStage(target, st, s.stages, m)
                    else changeStage(target, Stat.parse(s.stat), s.stages, m)
                }
            }
        }
        // special secondary effects
        when (e) {
            "REMOVE_HELD_ITEM" -> if (!t.fainted && t.item != It.NONE && ability(t) != Ab.STICKY_HOLD) {
                val lost = t.item
                t.item = It.NONE; t.lostItem = lost
                msg("${Josa.eunNeun(nm(m))} ${nm(t)}의 ${Josa.eulReul(itName(lost))} 떨어뜨렸다!")
            }
            "EAT_BERRY" -> if (t.item != It.NONE && It.isBerry(t.item) && ability(t) != Ab.STICKY_HOLD) {
                val b = t.item
                t.item = It.NONE; t.lostItem = b
                msg("${Josa.eunNeun(nm(m))} ${nm(t)}의 ${Josa.eulReul(itName(b))} 빼앗아 먹었다!")
                eatBerry(m, b, forced = true)
            }
            "HIT_FLY" -> Unit
        }
    }

    /**
     * Drags [t] out and sends in a random healthy party member. Shared by Whirlwind/Roar (FORCE_SWITCH)
     * and the damaging Dragon Tail / Circle Throw (CIRCLE_THROW). Callers must ensure a target exists
     * and the move is allowed (Suction Cups / Ingrain are checked by the caller for status moves; for
     * Dragon Tail they simply fail to drag after the hit, handled where this is called).
     */
    private suspend fun dragOut(m: BattleMon, t: BattleMon) {
        val side = sideOf(t)
        val opts = side.switchTargets()
        if (opts.isEmpty()) return
        val idx = opts[rng.next(opts.size)]
        emit(BattleEvent.Withdraw(t.side))
        t.resetVolatile()
        side.active = idx
        val nmon = side.mon
        nmon.resetVolatile()
        setupIllusion(nmon)
        msg("${Josa.iGa(nm(nmon))} 끌려 나왔다!")
        emit(BattleEvent.SendOut(t.side, nmon))
        entryHazards(nmon)
        if (!nmon.fainted) onEntry(nmon) else faint(nmon)
    }

    // ------------------------------------------------------------------ status moves aimed at the foe
    private suspend fun statusMove(m: BattleMon, t: BattleMon, move: MoveData) {
        val e = move.effect
        // Gen 7: Dark-type Pokémon are immune to status moves boosted by the user's Prankster.
        if (ability(m) == Ab.PRANKSTER && t.hasType(Types.DARK) && m !== t) {
            msg("${Josa.eunNeun(nm(t))} 짓궂은 마음의 영향을 받지 않는다!"); return
        }
        // Gen 6+: Grass-types are immune to powder/spore moves (Sleep Powder, Stun Spore, Spore,
        // Cotton Spore, …), including Rage Powder's redirection (which is a no-op here anyway).
        if (move.key in powderMoves && m !== t && t.hasType(Types.GRASS)) {
            msg("${nm(t)}에게는 효과가 없는 것 같다…"); return
        }
        val behindSub = t.substituteHp > 0 && !move.has("BYPASS_SUBSTITUTE") && !move.has("SOUND")
        val subBlocks = behindSub && e !in setOf("FORCE_SWITCH", "DISABLE", "ENCORE", "TAUNT", "TORMENT", "FORESIGHT", "INFATUATE", "PREVENT_ESCAPE", "DECREASE_LAST_MOVE_PP", "SWITCH_HELD_ITEMS", "SUPRESS_ABILITY", "AVERAGE_HP", "COPY_STAT_CHANGES", "ALL_FAINT_3_TURNS")
        if (subBlocks) { msg("하지만 실패했다!"); return }
        // type immunity applies to Thunder Wave (Electric vs Ground) and similar typed status moves
        if (e == "STATUS_PARALYZE" && move.key == "thunder_wave" && effectiveness(move.type, m, t) == 0) {
            msg("${nm(t)}에게는 효과가 없는 것 같다…"); return
        }
        when (e) {
            "STATUS_SLEEP" -> trySetStatus(t, Status.SLEEP, m)
            "STATUS_PARALYZE" -> trySetStatus(t, Status.PARALYSIS, m)
            "STATUS_BURN" -> trySetStatus(t, Status.BURN, m)
            "STATUS_BADLY_POISON" -> trySetStatus(t, Status.TOXIC, m)
            "STATUS_CONFUSE" -> tryConfuse(t, m, silentFail = false)
            "ATK_UP_2_STATUS_CONFUSION" -> {
                changeStage(t, Stat.ATK, 2, m)
                tryConfuse(t, m, silentFail = false)
            }
            // Stat-lowering moves aimed at the foe (Growl, Leer, Tail Whip, String Shot, Tickle, Captivate, …).
            "ACC_DOWN", "ATK_DOWN", "ATK_DOWN_2", "DEF_DOWN", "DEF_DOWN_2", "SPEED_DOWN", "SPEED_DOWN_2",
            "SP_DEF_DOWN_2", "ATK_DEF_DOWN" ->
                for (s in move.stats) changeStage(t, Stat.parse(s.stat), s.stages, m, failMsg = true)
            "SP_ATK_DOWN_2_OPPOSITE_GENDER" -> {
                if (m.gender != 2 && t.gender != 2 && m.gender != t.gender && defAbility(m, t) != Ab.OBLIVIOUS)
                    changeStage(t, Stat.SPA, -2, m, failMsg = true)
                else msg("하지만 실패했다!")
            }
            "SP_ATK_UP_CAUSE_CONFUSION" -> { changeStage(t, Stat.SPA, 1, m); tryConfuse(t, m, silentFail = false) }
            "RESET_STAT_CHANGES" -> {
                for (x in sides) x.mon.stages.fill(0)
                msg("모든 포켓몬의 능력 변화가 원래대로 되돌아갔다!")
            }
            "SIMPLE_BEAM", "ENTRAINMENT", "REFLECT_TYPE", "POWER_SPLIT", "GUARD_SPLIT", "TELEKINESIS",
            "IGNORE_EVATION_REMOVE_DARK_IMMUNE", "QUASH", "MAKE_SHARED_MOVES_UNUSEABLE", "HEAL_PULSE" ->
                msg("하지만 실패했다!")
            "STATUS_POISON" -> trySetStatus(t, Status.POISON, m)
            "PREVENT_HEALING" -> if (t.healBlockTurns > 0) msg("하지만 실패했다!") else { t.healBlockTurns = 5; msg("${nm(t)}은 회복기술을 쓸 수 없게 되었다!") }
            "PREVENT_ITEM_USE" -> if (t.embargoTurns > 0) msg("하지만 실패했다!") else { t.embargoTurns = 5; msg("${nm(t)}은 도구를 쓸 수 없게 되었다!") }
            "STATUS_LEECH_SEED" -> when {
                t.hasType(Types.GRASS) -> msg("${nm(t)}에게는 효과가 없는 것 같다…")
                t.leechSeeded -> msg("하지만 실패했다!")
                else -> { t.leechSeeded = true; msg("${nm(t)}에게 씨앗을 심었다!") }
            }
            "STATUS_NIGHTMARE" -> if (t.status == Status.SLEEP && !t.nightmare) { t.nightmare = true; msg("${Josa.eunNeun(nm(t))} 악몽을 꾸기 시작했다!") } else msg("하지만 실패했다!")
            "STATUS_SLEEP_NEXT_TURN" -> if (t.status == Status.NONE && t.yawnTurns == 0 && canSleep(t, m)) {
                t.yawnTurns = 2; msg("${Josa.eunNeun(nm(t))} 졸음이 오기 시작했다!")
            } else msg("하지만 실패했다!")
            "INFATUATE" -> when {
                // Needs opposite, known genders; fails if already infatuated or the target is Oblivious.
                m.gender == 2 || t.gender == 2 || m.gender == t.gender || t.infatuatedWith != null -> msg("하지만 실패했다!")
                defAbility(m, t) == Ab.OBLIVIOUS -> { msg("${nm(t)}의 둔감!"); msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!") }
                else -> {
                    t.infatuatedWith = m
                    msg("${Josa.eunNeun(nm(t))} 헤롱헤롱해졌다!")
                    // Destiny Knot: the newly infatuated holder infatuates the attacker back.
                    if (item(t) == It.DESTINY_KNOT && m.infatuatedWith == null && ability(m) != Ab.OBLIVIOUS &&
                        m.gender != 2 && t.gender != 2 && m.gender != t.gender) {
                        m.infatuatedWith = t
                        msg("${nm(t)}의 운명의매듭으로 ${Josa.iGa(nm(m))} 헤롱헤롱해졌다!")
                    }
                }
            }
            "DISABLE" -> {
                val last = t.lastMove
                if (last == null || t.disabledMove != null || t.moves.none { it.move.id == last.id && it.pp > 0 }) msg("하지만 실패했다!")
                else { t.disabledMove = last; t.disableTurns = 4; msg("${nm(t)}의 ${Josa.eulReul(mvName(last))} 봉인했다!") }
            }
            "ENCORE" -> {
                val last = t.lastMove
                if (last == null || t.encoreMove != null || last.effect in setOf("ENCORE", "COPY_MOVE_FOR_BATTLE", "STRUGGLE") ||
                    t.moves.none { it.move.id == last.id && it.pp > 0 }) msg("하지만 실패했다!")
                else { t.encoreMove = last; t.encoreTurns = 3; msg("${Josa.eunNeun(nm(t))} 앙코르를 받았다!") }
            }
            "TAUNT" -> if (t.tauntTurns > 0) msg("하지만 실패했다!") else {
                t.tauntTurns = if (t.movedThisTurn) 4 else 3
                msg("${Josa.eunNeun(nm(t))} 도발에 넘어갔다!")
            }
            "TORMENT" -> if (t.tormented) msg("하지만 실패했다!") else { t.tormented = true; msg("${Josa.eunNeun(nm(t))} 트집을 잡혔다!") }
            "FORESIGHT" -> { t.foresighted = true; msg("${Josa.eunNeun(nm(m))} ${Josa.eulReul(nm(t))} 꿰뚫어 보았다!") }
            // Gen 6+: Mean Look/Block/Spider Web fail against Ghost-types (they can't be trapped).
            "PREVENT_ESCAPE" -> if (t.trapped || t.hasType(Types.GHOST)) msg("하지만 실패했다!") else { t.trapped = true; msg("${Josa.eunNeun(nm(t))} 이제 도망갈 수 없다!") }
            "DECREASE_LAST_MOVE_PP" -> {
                val s = t.moves.firstOrNull { it.move.id == t.lastMove?.id }
                if (s == null || s.pp == 0) msg("하지만 실패했다!")
                else { val n = minOf(4, s.pp); s.pp -= n; msg("${nm(t)}의 ${Josa.eulReul(mvName(s.move))} ${n}만큼 줄였다!") }
            }
            "FORCE_SWITCH" -> {
                when {
                    defAbility(m, t) == Ab.SUCTION_CUPS -> { msg("${nm(t)}의 흡반!"); msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!") }
                    t.ingrained -> msg("${Josa.eunNeun(nm(t))} 뿌리를 내리고 있다!")
                    sideOf(t).switchTargets().isEmpty() -> msg("하지만 실패했다!")
                    else -> dragOut(m, t)
                }
            }
            "SWITCH_HELD_ITEMS" -> {
                // Trick / Switcheroo. Fails if both have no item, the target has Sticky Hold, or either
                // holds an untradeable item (Z-Crystal / a matching Mega Stone), matching Gen 6+.
                if ((m.item == It.NONE && t.item == It.NONE) || ability(t) == Ab.STICKY_HOLD ||
                    !tradeable(m) || !tradeable(t)
                ) msg("하지만 실패했다!")
                else {
                    val a = m.item; m.item = t.item; t.item = a
                    m.choiceMove = null; t.choiceMove = null
                    msg("${Josa.eunNeun(nm(m))} 서로의 도구를 바꿨다!")
                    if (m.item != It.NONE) msg("${Josa.eunNeun(nm(m))} ${Josa.eulReul(itName(m.item))} 받았다!")
                    if (t.item != It.NONE) msg("${Josa.eunNeun(nm(t))} ${Josa.eulReul(itName(t.item))} 받았다!")
                }
            }
            "SUPRESS_ABILITY" -> if (t.abilitySuppressed) msg("하지만 실패했다!") else {
                t.abilitySuppressed = true; msg("${nm(t)}의 특성이 효과를 잃었다!")
            }
            "AVERAGE_HP" -> {
                val avg = (m.hp + t.hp) / 2
                setHp(m, minOf(avg, m.maxHp)); setHp(t, minOf(avg, t.maxHp))
                msg("서로의 체력을 나눴다!")
            }
            "COPY_STAT_CHANGES" -> { t.stages.copyInto(m.stages); msg("${Josa.eunNeun(nm(m))} ${nm(t)}의 능력 변화를 복사했다!") }
            "COPY_MOVE_FOR_BATTLE" -> {
                val last = t.lastMove
                val idx = m.moves.indexOfFirst { it.move.effect == "COPY_MOVE_FOR_BATTLE" }
                if (last == null || idx < 0 || m.moves.any { it.move.id == last.id } || last.effect == "STRUGGLE") msg("하지만 실패했다!")
                else {
                    m.mimicOriginal = idx to m.moves[idx]
                    m.moves[idx] = MoveSlot(last, last.pp, last.pp)
                    msg("${Josa.eunNeun(nm(m))} ${Josa.eulReul(mvName(last))} 배웠다!")
                }
            }
            "CURSE" -> curse(m, t)
            "FAINT_AND_ATK_SP_ATK_DOWN_2" -> {
                changeStage(t, Stat.ATK, -2, m); changeStage(t, Stat.SPA, -2, m)
                damage(m, m.hp); faint(m)
            }
            "ALL_FAINT_3_TURNS" -> perishSong(m)
            else -> msg("하지만 실패했다!")
        }
    }

    private suspend fun curse(m: BattleMon, t: BattleMon) {
        if (m.hasType(Types.GHOST)) {
            if (t.cursed) { msg("하지만 실패했다!"); return }
            damage(m, maxOf(1, m.maxHp / 2))
            t.cursed = true
            msg("${Josa.eunNeun(nm(m))} 자신의 체력을 깎아서 ${nm(t)}에게 저주를 걸었다!")
            if (m.fainted) faint(m)
        } else {
            changeStage(m, Stat.SPE, -1, m)
            changeStage(m, Stat.ATK, 1, m)
            changeStage(m, Stat.DEF, 1, m)
        }
    }

    private suspend fun perishSong(m: BattleMon) {
        msg("멸망의노래를 들은 포켓몬은 3턴 후에 쓰러진다!")
        for (s in sides) {
            val x = s.mon
            if (!x.fainted && x.perishCount < 0 && ability(x) != Ab.SOUNDPROOF) x.perishCount = 3
        }
    }

    // ------------------------------------------------------------------ moves on the user's side / the field
    private suspend fun selfMove(m: BattleMon, move: MoveData) {
        val e = move.effect
        val f = foe(m)
        if (m.healBlockTurns > 0 && move.has("HEAL")) {
            msg("${Josa.eunNeun(nm(m))} 회복봉인으로 기술을 쓸 수 없다!")
            return
        }
        when (e) {
            // Any self-targeting stat-boost move, driven by its stat_changes data
            // (Dragon Dance, Calm Mind, Swords Dance, Quiver Dance, Shift Gear, Coil, Hone Claws, Work Up,
            // Cotton Guard, Nasty Plot, Agility, …). "RAISE_STATS" is the USUM extractor's name for a pure
            // stat-up self move; "STAT_CHANGE" covers mixed up/down self moves (e.g. Shell Smash style).
            // Legacy BW2 effect names are kept for backward compatibility.
            "RAISE_STATS", "STAT_CHANGE",
            "ATK_UP", "ATK_UP_2", "DEF_UP_2", "SPEED_UP_2", "SP_DEF_UP_2", "SP_ATK_UP_2", "EVA_UP", "ATK_DEF_UP",
            "ATK_SPD_UP", "SP_ATK_SP_DEF_UP", "DEF_SPD_UP", "QUIVER_DANCE", "SHIFT_GEAR", "COIL", "HONE_CLAWS",
            "WORK_UP", "COTTON_GUARD", "DEF_UP_DOUBLE_ROLLOUT_POWER" -> {
                val stats = move.stats.ifEmpty { statlessBoost(e) }
                // Fail only when the move is purely stat-raising and every raised stat is already maxed.
                val rising = stats.filter { it.stages > 0 }
                if (rising.isNotEmpty() && rising.size == stats.size &&
                    rising.all { m.stages[Stat.parse(it.stat).coerceAtLeast(1)] >= 6 }
                ) {
                    msg("${nm(m)}의 능력은 더 이상 올라가지 않는다!"); return
                }
                for (s in stats) changeStage(m, Stat.parse(s.stat), s.stages, m)
            }
            "SHELL_SMASH" -> {
                changeStage(m, Stat.DEF, -1, m, failMsg = false); changeStage(m, Stat.SPD, -1, m, failMsg = false)
                changeStage(m, Stat.ATK, 2, m); changeStage(m, Stat.SPA, 2, m); changeStage(m, Stat.SPE, 2, m)
            }
            "AUTOTOMIZE" -> changeStage(m, Stat.SPE, 2, m)
            "CRIT_UP_2" -> if (m.focusEnergy) msg("하지만 실패했다!") else { m.focusEnergy = true; msg("${nm(m)}은 힘이 넘쳐흐른다!") }
            "DOUBLE_SPEED_3_TURNS" -> {
                val s = sideOf(m)
                if (s.tailwind > 0) msg("하지만 실패했다!") else { s.tailwind = 4; msg("${if (m.side == 0) "" else "상대 "}바람이 등 뒤에서 불기 시작했다!") }
            }
            "PREVENT_STATUS" -> { val s = sideOf(m); if (s.safeguard > 0) msg("하지만 실패했다!") else { s.safeguard = 5; msg("${if (m.side == 0) "" else "상대 "}팀이 세이프가드에 둘러싸였다!") } }
            "PREVENT_STAT_REDUCTION" -> { val s = sideOf(m); if (s.mist > 0) msg("하지만 실패했다!") else { s.mist = 5; msg("${if (m.side == 0) "" else "상대 "}팀이 흰안개에 둘러싸였다!") } }
            "HEAL_STATUS", "CURE_PARTY_STATUS" -> {
                if (m.status != Status.NONE) { m.status = Status.NONE; emit(BattleEvent.StatusChanged(m.side)); msg("${nm(m)}의 상태 이상이 나았다!") }
                else msg("하지만 실패했다!")
            }
            "HEAL_IN_3_TURNS" -> { m.wishTurns = 2; m.wishHp = m.maxHp / 2; msg("${nm(m)}은 소원을 빌었다!") }
            "HEAL_PULSE" -> msg("하지만 실패했다!")
            "FAINT_AND_FULL_HEAL_NEXT_MON" -> {
                if (sideOf(m).switchTargets().isEmpty()) { msg("하지만 실패했다!"); return }
                sideOf(m).healingWish = true
                damage(m, m.hp); faint(m)
            }
            "GRAVITY" -> if (gravityTurns > 0) msg("하지만 실패했다!") else { gravityTurns = 5; msg("중력이 강해졌다!") }
            "WONDER_ROOM", "HALVE_FIRE_DAMAGE", "APPLY_MAGIC_COAT", "STEAL_STATUS_MOVE", "MAKE_GLOBAL_TARGET",
            "ALLY_SWITCH", "BOOST_ALLY_POWER_BY_50_PERCENT", "QUICK_GUARD", "WIDE_GUARD", "USE_RANDOM_ALLY_MOVE",
            "NEXT_ATTACK_ALWAYS_HITS", "DEF_UP_DOUBLE_ROLLOUT_POWER_" -> msg("하지만 실패했다!")
            "CALL_RANDOM_MOVE" -> {
                val mv = data.moves.values.filter { it.power > 0 && !it.isStatus && it.effect !in setOf("ONE_HIT_KO") }.random()
                recordMove(m, mv)
                executeMove(m, mv, false, calledMove = true)
            }
            "CONFUSE_ALL" -> { tryConfuse(foe(m), m, silentFail = false) }
            "EVA_UP_2_MINIMIZE" -> { m.minimized = true; changeStage(m, Stat.EVA, 2, m, failMsg = true) }
            "SP_DEF_UP_DOUBLE_ELECTRIC_POWER" -> {
                m.charged = true; m.chargeTurnsLeft = 2
                msg("${Josa.eunNeun(nm(m))} 충전을 했다!")
                changeStage(m, Stat.SPD, 1, m)
            }
            "RESTORE_HALF_HP", "HEAL_HALF_REMOVE_FLYING_TYPE" -> {
                if (m.hp >= m.maxHp) { msg("${nm(m)}의 체력은 가득하다!"); return }
                heal(m, (m.maxHp + 1) / 2)
                if (e == "HEAL_HALF_REMOVE_FLYING_TYPE") m.roosted = true
                msg("${Josa.eunNeun(nm(m))} 체력을 회복했다!")
            }
            "HEAL_HALF_MORE_IN_SUN" -> {
                if (m.hp >= m.maxHp) { msg("${nm(m)}의 체력은 가득하다!"); return }
                val amt = when (effWeather()) {
                    Weather.SUN -> m.maxHp * 2 / 3
                    Weather.NONE -> (m.maxHp + 1) / 2
                    else -> m.maxHp / 4
                }
                heal(m, amt); msg("${Josa.eunNeun(nm(m))} 체력을 회복했다!")
            }
            "REST" -> when {
                m.hp >= m.maxHp -> msg("${nm(m)}의 체력은 가득하다!")
                ability(m) in setOf(Ab.INSOMNIA) || m.status == Status.SLEEP -> msg("하지만 실패했다!")
                else -> {
                    m.status = Status.SLEEP; m.sleepTurns = 3; m.toxicCounter = 0
                    emit(BattleEvent.StatusChanged(m.side))
                    msg("${Josa.eunNeun(nm(m))} 잠들어 건강해졌다!")
                    heal(m, m.maxHp)
                }
            }
            "MAX_ATK_LOSE_HALF_MAX_HP" -> {
                if (m.hp <= m.maxHp / 2 || m.stages[Stat.ATK] >= 6) { msg("하지만 실패했다!"); return }
                damage(m, m.maxHp / 2)
                m.stages[Stat.ATK] = 6
                emit(BattleEvent.StatAnim(m.side, true))
                msg("${Josa.eunNeun(nm(m))} 체력을 깎아서 공격을 최대로 올렸다!")
            }
            "PROTECT", "SURVIVE_WITH_1_HP" -> {
                // Consecutive Protect/Endure gets likelier to fail (1/2, 1/4, …). Protect still succeeds
                // when the user moves last (it just has nothing left to block), matching the games.
                val chance = 1 shl minOf(m.protectCount, 8)
                if (rng.next(chance) != 0) {
                    m.protectCount = 0; msg("하지만 실패했다!"); return
                }
                m.protectCount++
                if (e == "PROTECT") { m.protecting = true; msg("${Josa.eunNeun(nm(m))} 방어 태세에 들어갔다!") }
                else { m.enduring = true; msg("${Josa.eunNeun(nm(m))} 버티기 태세에 들어갔다!") }
                return
            }
            "SET_SUBSTITUTE" -> {
                val cost = m.maxHp / 4
                when {
                    m.substituteHp > 0 -> msg("${Josa.eunNeun(nm(m))} 이미 분신이 나와 있다!")
                    m.hp <= cost -> msg("하지만 분신을 만들기에는 체력이 모자랐다!")
                    else -> {
                        damage(m, cost); m.substituteHp = cost
                        emit(BattleEvent.Substitute(m.side, true))
                        msg("${nm(m)}의 분신이 나타났다!")
                    }
                }
            }
            "SET_REFLECT", "SET_LIGHT_SCREEN" -> {
                val s = sideOf(m)
                val turns = if (item(m) == It.LIGHT_CLAY) 8 else 5
                if (e == "SET_REFLECT") {
                    if (s.reflect > 0) { msg("하지만 실패했다!"); return }
                    s.reflect = turns; msg(if (m.side == 0) "리플렉터로 물리공격에 강해졌다!" else "상대는 리플렉터로 물리공격에 강해졌다!")
                } else {
                    if (s.lightScreen > 0) { msg("하지만 실패했다!"); return }
                    s.lightScreen = turns; msg(if (m.side == 0) "빛의장막으로 특수공격에 강해졌다!" else "상대는 빛의장막으로 특수공격에 강해졌다!")
                }
            }
            "WEATHER_SUN" -> setWeather(Weather.SUN, if (item(m) == It.HEAT_ROCK) 8 else 5, null)
            "WEATHER_RAIN" -> setWeather(Weather.RAIN, if (item(m) == It.DAMP_ROCK) 8 else 5, null)
            "WEATHER_SANDSTORM" -> setWeather(Weather.SAND, 5, null)
            "WEATHER_HAIL" -> setWeather(Weather.HAIL, if (item(m) == It.ICY_ROCK) 8 else 5, null)
            "TERRAIN_ELECTRIC" -> setTerrain(Terrain.ELECTRIC, m)
            "TERRAIN_GRASSY" -> setTerrain(Terrain.GRASSY, m)
            "TERRAIN_MISTY" -> setTerrain(Terrain.MISTY, m)
            "TERRAIN_PSYCHIC" -> setTerrain(Terrain.PSYCHIC, m)
            "TRICK_ROOM" -> {
                if (trickRoom > 0) { trickRoom = 0; msg("뒤틀린 시공이 원래대로 되돌아왔다!") }
                else { trickRoom = 5; msg("${Josa.iGa(nm(m))} 시공을 뒤틀었다!") }
            }
            "SET_SPIKES" -> {
                val s = sides[1 - m.side]
                if (s.spikes >= 3) { msg("하지만 실패했다!"); return }
                s.spikes++; msg(if (m.side == 0) "상대의 발밑에 압정이 뿌려졌다!" else "발밑에 압정이 뿌려졌다!")
            }
            "TOXIC_SPIKES" -> {
                val s = sides[1 - m.side]
                if (s.toxicSpikes >= 2) { msg("하지만 실패했다!"); return }
                s.toxicSpikes++; msg(if (m.side == 0) "상대의 발밑에 독압정이 뿌려졌다!" else "발밑에 독압정이 뿌려졌다!")
            }
            "STEALTH_ROCK" -> {
                val s = sides[1 - m.side]
                if (s.stealthRock) { msg("하지만 실패했다!"); return }
                s.stealthRock = true; msg(if (m.side == 0) "상대의 주변에 뾰족한 바위가 떠다니기 시작했다!" else "주변에 뾰족한 바위가 떠다니기 시작했다!")
            }
            "GROUND_TRAP_USER_CONTINUOUS_HEAL" -> if (m.ingrained) msg("하지만 실패했다!") else { m.ingrained = true; msg("${Josa.eunNeun(nm(m))} 뿌리를 뻗었다!") }
            "RESTORE_HP_EVERY_TURN" -> if (m.aquaRing) msg("하지만 실패했다!") else { m.aquaRing = true; msg("${Josa.eunNeun(nm(m))} 물의 베일을 둘러썼다!") }
            "GIVE_GROUND_IMMUNITY" -> if (m.magnetRiseTurns > 0 || m.ingrained) msg("하지만 실패했다!") else { m.magnetRiseTurns = 5; msg("${Josa.eunNeun(nm(m))} 전자력으로 떠올랐다!") }
            "STOCKPILE" -> {
                if (m.stockpile >= 3) { msg("하지만 실패했다!"); return }
                m.stockpile++
                msg("${Josa.eunNeun(nm(m))} ${m.stockpile}개 비축했다!")
                if (m.stages[Stat.DEF] < 6) { changeStage(m, Stat.DEF, 1, m); m.stockpileDef++ }
                if (m.stages[Stat.SPD] < 6) { changeStage(m, Stat.SPD, 1, m); m.stockpileSpd++ }
            }
            "SWALLOW" -> {
                if (m.stockpile == 0) { msg("하지만 실패했다!"); return }
                val amt = when (m.stockpile) { 1 -> m.maxHp / 4; 2 -> m.maxHp / 2; else -> m.maxHp }
                heal(m, amt); msg("${Josa.eunNeun(nm(m))} 체력을 회복했다!")
                m.stages[Stat.DEF] -= m.stockpileDef; m.stages[Stat.SPD] -= m.stockpileSpd
                m.stockpile = 0; m.stockpileDef = 0; m.stockpileSpd = 0
                msg("${nm(m)}의 비축 효과가 사라졌다!")
            }
            "RANDOM_STAT_UP_2" -> {
                val opts = (Stat.ATK..Stat.EVA).filter { m.stages[it] < 6 }
                if (opts.isEmpty()) { msg("하지만 실패했다!"); return }
                changeStage(m, opts[rng.next(opts.size)], 2, m)
            }
            "SWAP_ATK_DEF" -> {
                val a = m.stats[Stat.ATK]; m.stats[Stat.ATK] = m.stats[Stat.DEF]; m.stats[Stat.DEF] = a
                m.powerTrick = !m.powerTrick
                msg("${Josa.eunNeun(nm(m))} 공격과 방어를 바꿨다!")
            }
            "CONVERSION2" -> {
                val lt = m.lastHitMoveType
                if (lt < 0 || lt >= Types.NULL) { msg("하지만 실패했다!"); return }
                val opts = (0 until Types.NULL).filter { Types.single(lt, it) < 4 && !(m.types.size == 1 && m.types[0] == it) }
                if (opts.isEmpty()) { msg("하지만 실패했다!"); return }
                val nt = opts[rng.next(opts.size)]
                m.types = mutableListOf(nt)
                msg("${Josa.eunNeun(nm(m))} ${data.typeName(nt)}타입이 되었다!")
            }
            "PASS_STATS_AND_STATUS" -> {
                if (sideOf(m).switchTargets().isEmpty()) { msg("하지만 실패했다!"); return }
                pivot(m, batonPass = true)
            }
            "KO_MON_THAT_DEFEATED_USER" -> { m.destinyBond = true; msg("${Josa.eunNeun(nm(m))} 상대를 길동무로 삼으려 하고 있다!") }
            "REMOVE_ALL_PP_ON_DEFEAT" -> { m.grudge = true; msg("${Josa.eunNeun(nm(m))} 상대에게 원념을 품었다!") }
            "RECYCLE" -> {
                if (m.item != It.NONE || m.lostItem == It.NONE || !m.itemConsumed) { msg("하지만 실패했다!"); return }
                m.item = m.lostItem; m.lostItem = It.NONE; m.itemConsumed = false
                msg("${Josa.eunNeun(nm(m))} ${Josa.eulReul(itName(m.item))} 주웠다!")
            }
            "HIT_IN_3_TURNS" -> {
                val s = sides[1 - m.side]
                // Fails if a Future Sight is already pending against this side.
                if (s.futureSightTurns > 0 || s.futureSightMove != null) { msg("하지만 실패했다!"); return }
                s.futureSightTurns = 3; s.futureSightUser = m; s.futureSightMove = move
                msg("${Josa.eunNeun(nm(m))} 미래를 내다보았다!")
            }
            "ALL_FAINT_3_TURNS" -> perishSong(m)
            "CURSE" -> curse(m, f)
            else -> msg("하지만 실패했다!")
        }
    }

    // ------------------------------------------------------------------ stat stages and status
    suspend fun changeStage(t: BattleMon, stat: Int, amount: Int, source: BattleMon?, fromAbility: Boolean = false, failMsg: Boolean = true) {
        if (stat < 1 || t.fainted) return
        val name = Stat.NAMES_KO[stat]
        if (amount < 0 && source !== t) {
            if (sideOf(t).mist > 0) { if (failMsg) msg("${nm(t)}은 흰안개로 보호받고 있다!"); return }
            val a = if (source != null) defAbility(source, t) else ability(t)
            if (a == Ab.CLEAR_BODY || (a == Ab.HYPER_CUTTER && stat == Stat.ATK) ||
                ((a == Ab.KEEN_EYE || a == Ab.ILLUMINATE) && stat == Stat.ACC) || (a == Ab.BIG_PECKS && stat == Stat.DEF)
            ) {
                if (failMsg || fromAbility) msg("${nm(t)}의 ${abName(a)}로 ${Josa.iGa(name)} 떨어지지 않는다!")
                return
            }
            if (t.substituteHp > 0 && !fromAbility && source != null) { if (failMsg) msg("하지만 실패했다!"); return }
        }
        val cur = t.stages[stat]
        if (amount > 0 && cur >= 6) { if (failMsg) msg("${nm(t)}의 ${Josa.eunNeun(name)} 더 이상 올라가지 않는다!"); return }
        if (amount < 0 && cur <= -6) { if (failMsg) msg("${nm(t)}의 ${Josa.eunNeun(name)} 더 이상 떨어지지 않는다!"); return }
        val nv = (cur + amount).coerceIn(-6, 6)
        t.stages[stat] = nv
        emit(BattleEvent.StatAnim(t.side, amount > 0))
        val d = kotlin.math.abs(nv - cur)
        msg("${nm(t)}의 ${Josa.iGa(name)} " + when {
            amount > 0 && d >= 3 -> "마구 올라갔다!"
            amount > 0 && d == 2 -> "크게 올라갔다!"
            amount > 0 -> "올라갔다!"
            d >= 3 -> "뚝 떨어졌다!"
            d == 2 -> "크게 떨어졌다!"
            else -> "떨어졌다!"
        })
        if (amount < 0 && item(t) == It.WHITE_HERB) {
            for (i in 1..7) if (t.stages[i] < 0) t.stages[i] = 0
            consumeItem(t)
            msg("${Josa.eunNeun(nm(t))} 하양허브로 떨어진 능력을 원래대로 되돌렸다!")
        }
    }

    private fun canSleep(t: BattleMon, source: BattleMon?): Boolean {
        val a = if (source != null) defAbility(source, t) else ability(t)
        if (a == Ab.INSOMNIA) return false
        if (a == Ab.LEAF_GUARD && effWeather() == Weather.SUN) return false
        return true
    }

    suspend fun trySetStatus(t: BattleMon, s: Status, source: BattleMon?, silentFail: Boolean = false): Boolean {
        if (t.fainted) return false
        if (t.status != Status.NONE) {
            if (!silentFail) msg(if (t.status == s || (s == Status.POISON && t.status == Status.TOXIC) || (s == Status.TOXIC && t.status == Status.POISON))
                "${Josa.eunNeun(nm(t))} 이미 ${statusNoun(t.status)} 상태다!" else "하지만 실패했다!")
            return false
        }
        if (source != null && source !== t && sideOf(t).safeguard > 0) {
            if (!silentFail) msg("${nm(t)}은 세이프가드에 보호받고 있다!")
            return false
        }
        val a = if (source != null && source !== t) defAbility(source, t) else ability(t)
        val blockedAbility = when (s) {
            Status.SLEEP -> a == Ab.INSOMNIA
            Status.POISON, Status.TOXIC -> a == Ab.IMMUNITY
            Status.BURN -> a == Ab.WATER_VEIL
            else -> false
        } || (a == Ab.LEAF_GUARD && effWeather() == Weather.SUN)
        if (blockedAbility) {
            if (!silentFail) { msg("${nm(t)}의 ${abName(a)}!"); msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!") }
            return false
        }
        // Gen 7 terrain: grounded Pokémon resist status (Misty = all, Electric = sleep only).
        if (grounded(t) && (terrain == Terrain.MISTY || (terrain == Terrain.ELECTRIC && s == Status.SLEEP))) {
            if (!silentFail) msg("${Josa.eunNeun(nm(t))} 필드의 보호를 받고 있다!")
            return false
        }
        val typeImmune = when (s) {
            Status.POISON, Status.TOXIC -> t.hasType(Types.POISON) || t.hasType(Types.STEEL)
            Status.BURN -> t.hasType(Types.FIRE)
            Status.FREEZE -> t.hasType(Types.ICE) || effWeather() == Weather.SUN
            // Gen 6+: Electric-types can't be paralyzed (by any source — Body Slam, Glare, Static, …).
            Status.PARALYSIS -> t.hasType(Types.ELECTRIC)
            else -> false
        }
        if (typeImmune) { if (!silentFail) msg("${nm(t)}에게는 효과가 없는 것 같다…"); return false }
        if (source != null && source !== t && t.substituteHp > 0 && silentFail.not()) { msg("하지만 실패했다!"); return false }
        t.status = s
        when (s) {
            Status.SLEEP -> { t.sleepTurns = 1 + rng.next(3) + 1; msg("${Josa.eunNeun(nm(t))} 잠들어 버렸다!") }
            Status.POISON -> msg("${Josa.eunNeun(nm(t))} 독에 걸렸다!")
            Status.TOXIC -> { t.toxicCounter = 0; msg("${Josa.eunNeun(nm(t))} 맹독을 입었다!") }
            Status.BURN -> msg("${Josa.eunNeun(nm(t))} 화상을 입었다!")
            Status.FREEZE -> msg("${Josa.eunNeun(nm(t))} 얼어붙었다!")
            Status.PARALYSIS -> msg("${Josa.eunNeun(nm(t))} 마비되어 기술이 나오기 어려워졌다!")
            Status.NONE -> Unit
        }
        emit(BattleEvent.StatusChanged(t.side))
        // Synchronize passes poison, burn and paralysis back.
        if (source != null && source !== t && ability(t) == Ab.SYNCHRONIZE && s in setOf(Status.POISON, Status.TOXIC, Status.BURN, Status.PARALYSIS)) {
            msg("${nm(t)}의 싱크로!")
            trySetStatus(source, if (s == Status.TOXIC) Status.POISON else s, t, silentFail = true)
        }
        statusBerry(t)
        return true
    }

    private fun statusNoun(s: Status) = when (s) {
        Status.SLEEP -> "잠듦"; Status.POISON, Status.TOXIC -> "독"; Status.BURN -> "화상"
        Status.FREEZE -> "얼음"; Status.PARALYSIS -> "마비"; Status.NONE -> ""
    }

    private suspend fun tryConfuse(t: BattleMon, source: BattleMon, silentFail: Boolean) {
        if (t.fainted) return
        val a = defAbility(source, t)
        if (a == Ab.OWN_TEMPO) { if (!silentFail) { msg("${nm(t)}의 마이페이스!"); msg("${Josa.eunNeun(nm(t))} 영향을 받지 않았다!") }; return }
        if (t.confusion > 0) { if (!silentFail) msg("${Josa.eunNeun(nm(t))} 이미 혼란 상태다!"); return }
        if (t.substituteHp > 0 && !silentFail) { msg("하지만 실패했다!"); return }
        t.confusion = 2 + rng.next(4)
        msg("${Josa.eunNeun(nm(t))} 혼란에 빠졌다!")
        if (item(t) == It.PERSIM_BERRY || item(t) == It.LUM_BERRY) eatBerry(t, item(t), forced = false)
    }

    // ------------------------------------------------------------------ HP
    private suspend fun damage(m: BattleMon, amount: Int) {
        if (amount <= 0 || m.fainted) return
        val from = m.hp
        m.hp = maxOf(0, m.hp - amount)
        emit(BattleEvent.Hp(m.side, from, m.hp, m.maxHp))
        if (m.hp > 0) hpTriggers(m)
    }

    private suspend fun heal(m: BattleMon, amount: Int) {
        if (amount <= 0 || m.fainted || m.hp >= m.maxHp) return
        val from = m.hp
        m.hp = minOf(m.maxHp, m.hp + amount)
        emit(BattleEvent.Hp(m.side, from, m.hp, m.maxHp))
    }

    private suspend fun setHp(m: BattleMon, v: Int) {
        val from = m.hp
        m.hp = v.coerceIn(0, m.maxHp)
        if (from != m.hp) emit(BattleEvent.Hp(m.side, from, m.hp, m.maxHp))
    }

    private val announcedFaint = mutableSetOf<BattleMon>()

    private suspend fun faint(m: BattleMon) {
        if (!m.fainted || m in announcedFaint) return
        announcedFaint += m
        // Reveal the real species before the faint message/animation (no "풀렸다" line here).
        m.illusionSpecies = -1; m.illusionName = null
        m.lockedMove = null; m.chargingMove = null; m.semiInvulnerable = null
        emit(BattleEvent.Faint(m.side))
        msg("${Josa.eunNeun(nm(m))} 쓰러졌다!")
        if (sides.any { it.alive() == 0 }) checkOver()
    }

    private fun consumeItem(m: BattleMon) {
        if (m.item == It.NONE) return
        m.lostItem = m.item
        m.item = It.NONE
        m.itemConsumed = true
        m.choiceMove = null
    }

    /** HP-threshold items: Sitrus Berry at 1/2, pinch berries at 1/4 (1/2 with Gluttony). */
    private suspend fun hpTriggers(m: BattleMon) {
        if (m.fainted) return
        checkZenMode(m)
        val it = item(m)
        if (it == It.SITRUS_BERRY && m.hp * 2 <= m.maxHp) eatBerry(m, it, forced = false)
        val pinch = if (ability(m) == Ab.GLUTTONY) 2 else 4
        if (it in setOf(It.LIECHI_BERRY, It.SALAC_BERRY, It.PETAYA_BERRY, It.GANLON_BERRY) && m.hp * pinch <= m.maxHp) eatBerry(m, it, forced = false)
    }

    private suspend fun statusBerry(m: BattleMon) {
        val it = item(m)
        val cures = when (it) {
            It.CHERI_BERRY -> m.status == Status.PARALYSIS
            It.CHESTO_BERRY -> m.status == Status.SLEEP
            It.LUM_BERRY -> m.status != Status.NONE
            else -> false
        }
        if (cures) eatBerry(m, it, forced = false)
    }

    /** Eats a berry; forced = eaten through Pluck/Bug Bite/Fling, so it applies even when not needed. */
    private suspend fun eatBerry(m: BattleMon, berry: Int, forced: Boolean) {
        if (m.fainted) return
        val held = m.item == berry
        val name = itName(berry)
        fun use() { if (held) consumeItem(m) }
        when (berry) {
            It.SITRUS_BERRY -> if (forced || m.hp * 2 <= m.maxHp) {
                use(); heal(m, m.maxHp / 4); msg("${Josa.eunNeun(nm(m))} ${Josa.ro(name)} 체력을 회복했다!")
            }
            It.CHERI_BERRY -> if (forced || m.status == Status.PARALYSIS) {
                use(); if (m.status == Status.PARALYSIS) { m.status = Status.NONE; emit(BattleEvent.StatusChanged(m.side)); msg("${Josa.eunNeun(nm(m))} ${Josa.ro(name)} 마비가 풀렸다!") }
            }
            It.CHESTO_BERRY -> if (forced || m.status == Status.SLEEP) {
                use(); if (m.status == Status.SLEEP) { m.status = Status.NONE; emit(BattleEvent.StatusChanged(m.side)); msg("${Josa.eunNeun(nm(m))} ${Josa.ro(name)} 눈을 떴다!") }
            }
            It.PERSIM_BERRY -> if (forced || m.confusion > 0) {
                use(); if (m.confusion > 0) { m.confusion = 0; msg("${Josa.eunNeun(nm(m))} ${Josa.ro(name)} 혼란이 풀렸다!") }
            }
            It.LUM_BERRY -> if (forced || m.status != Status.NONE || m.confusion > 0) {
                use()
                if (m.status != Status.NONE || m.confusion > 0) {
                    m.status = Status.NONE; m.confusion = 0; emit(BattleEvent.StatusChanged(m.side))
                    msg("${Josa.eunNeun(nm(m))} ${Josa.ro(name)} 상태가 회복되었다!")
                }
            }
            It.LIECHI_BERRY, It.SALAC_BERRY, It.PETAYA_BERRY, It.GANLON_BERRY -> {
                val pinch = if (ability(m) == Ab.GLUTTONY) 2 else 4
                if (forced || m.hp * pinch <= m.maxHp) {
                    use()
                    msg("${Josa.eunNeun(nm(m))} ${Josa.eulReul(name)} 먹었다!")
                    changeStage(m, when (berry) {
                        It.LIECHI_BERRY -> Stat.ATK; It.SALAC_BERRY -> Stat.SPE
                        It.GANLON_BERRY -> Stat.DEF; else -> Stat.SPA
                    }, 1, m)
                }
            }
            else -> if (forced) use()
        }
    }

    // ------------------------------------------------------------------ weather
    private suspend fun setWeather(w: Weather, turns: Int, byAbility: BattleMon?) {
        if (weather == w && (byAbility != null || weatherTurns != 0)) {
            if (byAbility == null) { msg("하지만 실패했다!"); return }
        }
        if (byAbility != null) msg("${nm(byAbility)}의 ${abName(ability(byAbility))}!")
        weather = w
        weatherTurns = turns
        emit(BattleEvent.WeatherChanged(w))
        msg(when (w) {
            Weather.SUN -> "햇살이 강해졌다!"
            Weather.RAIN -> "비가 내리기 시작했다!"
            Weather.SAND -> "모래바람이 불기 시작했다!"
            Weather.HAIL -> "싸라기눈이 내리기 시작했다!"
            Weather.NONE -> ""
        })
    }

    private suspend fun setTerrain(terr: Terrain, user: BattleMon) {
        if (terrain == terr) { msg("하지만 실패했다!"); return }
        terrain = terr
        terrainTurns = 5
        emit(BattleEvent.TerrainChanged(terr))
        msg(when (terr) {
            Terrain.ELECTRIC -> "발밑에 전기가 흐르기 시작했다!"
            Terrain.GRASSY -> "발밑에 풀이 무성해졌다!"
            Terrain.MISTY -> "발밑에 안개가 자욱해졌다!"
            Terrain.PSYCHIC -> "발밑이 이상한 느낌으로 가득해졌다!"
            Terrain.NONE -> ""
        })
    }

    // ------------------------------------------------------------------ end of turn (Gen 5 order, simplified)
    private suspend fun endOfTurn() {
        val order = bySpeed(listOf(sides[0].mon, sides[1].mon))
        // weather
        if (weather != Weather.NONE) {
            if (weatherTurns > 0) weatherTurns--
            if (weatherTurns == 0) {
                msg(when (weather) {
                    Weather.SUN -> "햇살이 약해졌다!"; Weather.RAIN -> "비가 그쳤다!"
                    Weather.SAND -> "모래바람이 가라앉았다!"; Weather.HAIL -> "싸라기눈이 그쳤다!"; else -> ""
                })
                weather = Weather.NONE
                emit(BattleEvent.WeatherChanged(weather))
            } else {
                msg(when (weather) {
                    Weather.SUN -> "햇살이 강하다."; Weather.RAIN -> "비가 계속 내리고 있다."
                    Weather.SAND -> "모래바람이 세차게 분다."; Weather.HAIL -> "싸라기눈이 계속 내리고 있다."; else -> ""
                })
                val w = effWeather()
                for (m in order) {
                    if (m.fainted) continue
                    val a = ability(m)
                    when (w) {
                        Weather.SAND -> if (!(m.hasType(Types.ROCK) || m.hasType(Types.GROUND) || m.hasType(Types.STEEL) || a in setOf(Ab.SAND_VEIL, Ab.SAND_RUSH, Ab.SAND_FORCE, Ab.MAGIC_GUARD, Ab.OVERCOAT)) && m.semiInvulnerable !in setOf("DIG", "DIVE")) {
                            msg("모래바람이 ${Josa.eulReul(nm(m))} 덮쳤다!"); damage(m, maxOf(1, m.maxHp / 16))
                        }
                        Weather.HAIL -> if (a == Ab.ICE_BODY) {
                            if (m.hp < m.maxHp) { heal(m, maxOf(1, m.maxHp / 16)); msg("${nm(m)}의 ${abName(a)}로 체력이 회복되었다!") }
                        } else if (!m.hasType(Types.ICE) && a !in setOf(Ab.SNOW_CLOAK, Ab.MAGIC_GUARD, Ab.ICE_BODY, Ab.OVERCOAT) && m.semiInvulnerable !in setOf("DIG", "DIVE")) {
                            msg("싸라기눈이 ${Josa.eulReul(nm(m))} 덮쳤다!"); damage(m, maxOf(1, m.maxHp / 16))
                        }
                        Weather.RAIN -> when (a) {
                            Ab.RAIN_DISH -> if (m.hp < m.maxHp) { heal(m, maxOf(1, m.maxHp / 16)); msg("${nm(m)}의 ${abName(a)}로 체력이 회복되었다!") }
                            Ab.DRY_SKIN -> if (m.hp < m.maxHp) { heal(m, maxOf(1, m.maxHp / 8)); msg("${nm(m)}의 ${abName(a)}로 체력이 회복되었다!") }
                            Ab.HYDRATION -> if (m.status != Status.NONE) { m.status = Status.NONE; emit(BattleEvent.StatusChanged(m.side)); msg("${nm(m)}의 ${abName(a)}로 상태 이상이 나았다!") }
                        }
                        Weather.SUN -> if (a == Ab.DRY_SKIN) { msg("${nm(m)}의 ${abName(a)}!"); damage(m, maxOf(1, m.maxHp / 8)) }
                        else -> Unit
                    }
                    if (m.fainted) faint(m)
                }
            }
        }
        // Terrain: Grassy Terrain heals grounded Pokémon, then the terrain counts down.
        if (terrain != Terrain.NONE) {
            if (terrain == Terrain.GRASSY) {
                for (m in order) if (!m.fainted && grounded(m) && m.hp < m.maxHp) {
                    heal(m, maxOf(1, m.maxHp / 16)); msg("${Josa.eunNeun(nm(m))} 그래스필드로 체력을 회복했다!")
                }
            }
            if (terrainTurns > 0 && --terrainTurns == 0) {
                msg(when (terrain) {
                    Terrain.ELECTRIC -> "발밑의 전기가 사라졌다!"; Terrain.GRASSY -> "발밑의 풀이 사라졌다!"
                    Terrain.MISTY -> "발밑의 안개가 걷혔다!"; Terrain.PSYCHIC -> "발밑의 이상한 느낌이 사라졌다!"; else -> ""
                })
                terrain = Terrain.NONE
                emit(BattleEvent.TerrainChanged(terrain))
            }
        }
        // Future Sight
        for (s in sides) {
            if (s.futureSightTurns > 0 && --s.futureSightTurns == 0) {
                val t = s.mon
                val user = s.futureSightUser
                val mv = s.futureSightMove
                s.futureSightUser = null; s.futureSightMove = null
                if (!t.fainted && user != null && mv != null) {
                    msg("${Josa.eunNeun(nm(t))} 미래예지 공격을 받았다!")
                    val r = calcDamage(user, t, mv, mv.type, false, forceCrit = false)
                    if (r.eff == 0) msg("${nm(t)}에게는 효과가 없는 것 같다…")
                    else {
                        dealDamage(user, t, mv, r.damage, r.eff, false, showEff = true)
                        if (t.fainted) faint(t)
                    }
                }
            }
        }
        for (m in order) {
            if (m.fainted) continue
            val a = ability(m)
            val it = item(m)
            // Wish resolves on its own mon
            if (m.wishTurns > 0 && --m.wishTurns == 0 && m.hp < m.maxHp) {
                heal(m, m.wishHp); msg("${nm(m)}의 소원이 이루어졌다!")
            }
            if (m.healBlockTurns > 0) m.healBlockTurns--
            if (m.embargoTurns > 0) m.embargoTurns--
            // Shed Skin: 30% to cure status
            if (a == Ab.SHED_SKIN && m.status != Status.NONE && rng.chance(30)) {
                m.status = Status.NONE; emit(BattleEvent.StatusChanged(m.side)); msg("${nm(m)}의 탈피로 상태 이상이 나았다!")
            }
            // Aqua Ring, Ingrain
            if (m.aquaRing && m.hp < m.maxHp) {
                heal(m, maxOf(1, m.maxHp / 16 * (if (it == It.BIG_ROOT) 13 else 10) / 10)); msg("${Josa.eunNeun(nm(m))} 물의 베일로 체력을 회복했다!")
            }
            if (m.ingrained && m.hp < m.maxHp) {
                heal(m, maxOf(1, m.maxHp / 16 * (if (it == It.BIG_ROOT) 13 else 10) / 10)); msg("${Josa.eunNeun(nm(m))} 뿌리로 양분을 흡수했다!")
            }
            // Leftovers / Black Sludge
            if (it == It.LEFTOVERS && m.hp < m.maxHp) { heal(m, maxOf(1, m.maxHp / 16)); msg("${Josa.eunNeun(nm(m))} 먹다남은음식으로 조금 회복했다!") }
            if (it == It.BLACK_SLUDGE) {
                if (m.hasType(Types.POISON)) { if (m.hp < m.maxHp) { heal(m, maxOf(1, m.maxHp / 16)); msg("${Josa.eunNeun(nm(m))} 검은오물로 조금 회복했다!") } }
                else { msg("${Josa.eunNeun(nm(m))} 검은오물로 데미지를 입었다!"); damage(m, maxOf(1, m.maxHp / 8)) }
            }
            if (m.fainted) { faint(m); continue }
            val magicGuard = a == Ab.MAGIC_GUARD
            // Leech Seed
            if (m.leechSeeded && !magicGuard) {
                val f = foe(m)
                val d = minOf(m.hp, maxOf(1, m.maxHp / 8))
                msg("씨뿌리기가 ${nm(m)}의 체력을 빼앗는다!")
                damage(m, d)
                if (!f.fainted) {
                    var h = d
                    if (item(f) == It.BIG_ROOT) h = h * 13 / 10
                    if (a == Ab.LIQUID_OOZE) { damage(f, h); if (f.fainted) faint(f) } else heal(f, h)
                }
                if (m.fainted) { faint(m); continue }
            }
            // poison / burn
            when (if (magicGuard && a != Ab.POISON_HEAL) Status.NONE else m.status) {
                Status.POISON, Status.TOXIC -> {
                    if (m.status == Status.TOXIC) m.toxicCounter++
                    if (a == Ab.POISON_HEAL) {
                        if (m.hp < m.maxHp) { heal(m, maxOf(1, m.maxHp / 8)); msg("${nm(m)}의 ${abName(a)}로 체력이 회복되었다!") }
                    } else {
                        val d = if (m.status == Status.TOXIC) maxOf(1, m.maxHp * minOf(m.toxicCounter, 15) / 16) else maxOf(1, m.maxHp / 8)
                        msg("${Josa.eunNeun(nm(m))} 독의 데미지를 입고 있다!")
                        damage(m, d)
                    }
                }
                Status.BURN -> {
                    val d = maxOf(1, if (a == Ab.HEATPROOF) m.maxHp / 16 else m.maxHp / 8)
                    msg("${Josa.eunNeun(nm(m))} 화상의 데미지를 입고 있다!")
                    damage(m, d)
                }
                else -> Unit
            }
            if (m.fainted) { faint(m); continue }
            if (m.nightmare && m.status == Status.SLEEP) {
                if (!magicGuard) { msg("${Josa.eunNeun(nm(m))} 악몽에 시달리고 있다!"); damage(m, maxOf(1, m.maxHp / 4)) }
                if (m.fainted) { faint(m); continue }
            } else m.nightmare = false
            if (m.cursed && !magicGuard) {
                msg("${Josa.eunNeun(nm(m))} 저주받고 있다!"); damage(m, maxOf(1, m.maxHp / 4))
                if (m.fainted) { faint(m); continue }
            }
            if (m.bindTurns > 0) {
                if (magicGuard) { m.bindTurns = 0 }
                m.bindTurns--
                if (m.bindTurns == 0) msg("${Josa.eunNeun(nm(m))} ${mvName(m.bindMove!!)}에서 풀려났다!")
                else {
                    msg("${Josa.eunNeun(nm(m))} ${mvName(m.bindMove!!)}의 데미지를 입고 있다!")
                    damage(m, maxOf(1, m.maxHp / 16))
                    if (m.fainted) { faint(m); continue }
                }
            }
            if (m.tauntTurns > 0 && --m.tauntTurns == 0) msg("${Josa.eunNeun(nm(m))} 도발의 효과가 풀렸다!")
            if (m.encoreTurns > 0 && --m.encoreTurns == 0) { m.encoreMove = null; msg("${nm(m)}의 앙코르 상태가 풀렸다!") }
            if (m.encoreMove != null && m.moves.firstOrNull { it.move.id == m.encoreMove?.id }?.pp == 0) { m.encoreMove = null; m.encoreTurns = 0 }
            if (m.disableTurns > 0 && --m.disableTurns == 0) { m.disabledMove = null; msg("${nm(m)}의 사슬묶기가 풀렸다!") }
            if (m.magnetRiseTurns > 0 && --m.magnetRiseTurns == 0) msg("${nm(m)}의 전자부유 효과가 사라졌다!")
            if (m.chargeTurnsLeft > 0 && --m.chargeTurnsLeft == 0) m.charged = false
            if (m.yawnTurns > 0 && --m.yawnTurns == 0) trySetStatus(m, Status.SLEEP, null, silentFail = true)
            if (m.perishCount > 0) {
                m.perishCount--
                msg("${nm(m)}의 멸망카운트가 ${m.perishCount}이 되었다!")
                if (m.perishCount == 0) { damage(m, m.hp); faint(m); continue }
            }
            // Toxic Orb
            if (it == It.TOXIC_ORB && m.status == Status.NONE) trySetStatus(m, Status.TOXIC, null, silentFail = true)
            if (it == It.FLAME_ORB && m.status == Status.NONE) trySetStatus(m, Status.BURN, null, silentFail = true)
            // Speed Boost / Slow Start
            if (a == Ab.SPEED_BOOST && m.turnsOnField > 0 && m.stages[Stat.SPE] < 6) { msg("${nm(m)}의 가속!"); changeStage(m, Stat.SPE, 1, m) }
            if (a == Ab.SLOW_START) { m.slowStartTurns++; if (m.slowStartTurns == 5) msg("${Josa.eunNeun(nm(m))} 드디어 컨디션을 되찾았다!") }
        }
        for (s in sides) {
            if (s.reflect > 0 && --s.reflect == 0) msg(if (s.index == 0) "리플렉터의 효과가 사라졌다!" else "상대의 리플렉터의 효과가 사라졌다!")
            if (s.lightScreen > 0 && --s.lightScreen == 0) msg(if (s.index == 0) "빛의장막의 효과가 사라졌다!" else "상대의 빛의장막의 효과가 사라졌다!")
            if (s.tailwind > 0 && --s.tailwind == 0) msg(if (s.index == 0) "등 뒤의 바람이 멈췄다!" else "상대의 등 뒤의 바람이 멈췄다!")
            if (s.safeguard > 0 && --s.safeguard == 0) msg(if (s.index == 0) "세이프가드의 효과가 사라졌다!" else "상대의 세이프가드의 효과가 사라졌다!")
            if (s.mist > 0 && --s.mist == 0) msg(if (s.index == 0) "흰안개가 사라졌다!" else "상대의 흰안개가 사라졌다!")
        }
        if (trickRoom > 0 && --trickRoom == 0) msg("뒤틀린 시공이 원래대로 되돌아왔다!")
        if (gravityTurns > 0 && --gravityTurns == 0) msg("중력이 원래대로 되돌아왔다!")
        for (s in sides) {
            val m = s.mon
            if (!m.fainted) m.turnsOnField++
            if (m.lastMove?.effect !in setOf("PROTECT", "SURVIVE_WITH_1_HP")) m.protectCount = 0
        }
        checkOver()
    }
}
