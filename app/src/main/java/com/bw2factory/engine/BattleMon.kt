package com.bw2factory.engine

import com.bw2factory.data.GameData
import com.bw2factory.data.MoveData

class MoveSlot(var move: MoveData, var pp: Int, var maxPp: Int)

/** A Pokémon in battle: permanent state (HP, status, PP, item) plus volatile state that clears on switch-out. */
class BattleMon(val data: GameData, val info: MonInfo, val side: Int) {
    val species = info.species
    val level = info.mon.level
    val gender = info.gender
    val baseStats = info.stats.copyOf()
    var stats = info.stats.copyOf()
    val maxHp = stats[Stat.HP]
    var hp = maxHp
    var status = Status.NONE
    var sleepTurns = 0
    var toxicCounter = 0
    var baseAbility = info.ability
    var ability = info.ability
    var item = info.set.item
    /** The item consumed or lost this battle, for Recycle / Unburden. */
    var lostItem = 0
    var itemConsumed = false
    val moves: MutableList<MoveSlot> = info.set.moves.map { id ->
        val m = data.move(id)
        // Factory Pokémon have no PP Ups (sub_0204B834 sets ppUp = 0).
        MoveSlot(m, m.pp, m.pp)
    }.toMutableList()
    /** Mimic replaces a slot until switch-out: (slot index, original slot). */
    var mimicOriginal: Pair<Int, MoveSlot>? = null
    val friendship = info.friendship

    /**
     * Hidden Power type (engine Type index). Factory Pokémon use one flat IV for all six stats,
     * so the type bit of every stat is identical: even IV -> type index 0 (Fighting), odd IV ->
     * index 15 (Dark). Hidden Power type index h maps to engine type h+1.
     */
    val hiddenPowerType: Int = if (info.mon.iv % 2 == 0) Types.FIGHTING else Types.DARK

    val name: String get() = species.nameKo

    // ---- Illusion (일루전): while active, the mon is shown as another party member.
    /** Species id to display instead of the real one, or -1 when no illusion is active. */
    var illusionSpecies: Int = -1
    /** Korean name to display instead of the real one, or null when no illusion is active. */
    var illusionName: String? = null
    val illusionActive: Boolean get() = illusionSpecies >= 0
    /** The species id the UI should draw (the disguise while Illusion is up). */
    val displaySpecies: Int get() = if (illusionSpecies >= 0) illusionSpecies else species.id
    /** The name the UI/messages should show (the disguise while Illusion is up). */
    val displayName: String get() = illusionName ?: species.nameKo
    val fainted get() = hp <= 0

    // ---- volatile state (reset on switch)
    var types: MutableList<Int> = species.types.toMutableList()
    val stages = IntArray(8)
    var confusion = 0
    var flinch = false
    var leechSeeded = false
    var substituteHp = 0
    var protectCount = 0
    var protecting = false
    var enduring = false
    var lockedMove: MoveData? = null
    var lockedTurns = 0
    var recharging = false
    var chargingMove: MoveData? = null
    /** "FLY", "DIG", "DIVE" while in the semi-invulnerable turn */
    var semiInvulnerable: String? = null
    var encoreMove: MoveData? = null
    var encoreTurns = 0
    var tauntTurns = 0
    var tormented = false
    var disabledMove: MoveData? = null
    var disableTurns = 0
    /** The Pokémon this one is infatuated with; infatuation ends when that Pokémon leaves the field. */
    var infatuatedWith: BattleMon? = null
    var nightmare = false
    var cursed = false
    var perishCount = -1
    var ingrained = false
    var aquaRing = false
    var magnetRiseTurns = 0
    var yawnTurns = 0
    var destinyBond = false
    var grudge = false
    var foresighted = false
    var choiceMove: MoveData? = null
    var stockpile = 0
    var stockpileDef = 0
    var stockpileSpd = 0
    var bindTurns = 0
    var bindMove: MoveData? = null
    var bindByGripClaw = false
    var trapped = false
    var lastMove: MoveData? = null
    var lastMoveSlotPpTarget: MoveSlot? = null
    var movedThisTurn = false
    var tookDamageThisTurn = false
    var lastHitBy: BattleMon? = null
    var lastDamagePhysical = 0
    var lastDamageSpecial = 0
    var lastHitMoveType = -1
    var truantLoafing = false
    var slowStartTurns = 0
    var flashFire = false
    var charged = false
    var chargeTurnsLeft = 0
    var minimized = false
    var roosted = false
    var powerTrick = false
    var abilitySuppressed = false
    var turnsOnField = 0
    var usedMoves = mutableSetOf<Int>()
    var metronomeCount = 0
    var metronomeMove: MoveData? = null
    var focusPunchCharging = false
    var focusEnergy = false
    var healBlockTurns = 0
    var embargoTurns = 0
    var wishTurns = 0
    var wishHp = 0
    var gemConsumedType = -1

    fun resetVolatile() {
        types = species.types.toMutableList()
        stages.fill(0)
        confusion = 0; flinch = false; leechSeeded = false; substituteHp = 0
        protectCount = 0; protecting = false; enduring = false
        lockedMove = null; lockedTurns = 0; recharging = false; chargingMove = null; semiInvulnerable = null
        encoreMove = null; encoreTurns = 0; tauntTurns = 0; tormented = false
        disabledMove = null; disableTurns = 0; infatuatedWith = null; nightmare = false; cursed = false
        perishCount = -1; ingrained = false; aquaRing = false; magnetRiseTurns = 0; yawnTurns = 0
        destinyBond = false; grudge = false; foresighted = false; choiceMove = null
        stockpile = 0; stockpileDef = 0; stockpileSpd = 0
        bindTurns = 0; bindMove = null; bindByGripClaw = false; trapped = false
        lastMove = null; movedThisTurn = false; tookDamageThisTurn = false; lastHitBy = null
        lastDamagePhysical = 0; lastDamageSpecial = 0; lastHitMoveType = -1
        truantLoafing = false; slowStartTurns = 0; flashFire = false; charged = false; chargeTurnsLeft = 0
        minimized = false; roosted = false; abilitySuppressed = false; turnsOnField = 0
        usedMoves.clear(); metronomeCount = 0; metronomeMove = null; focusPunchCharging = false
        focusEnergy = false; healBlockTurns = 0; embargoTurns = 0; wishTurns = 0; wishHp = 0; gemConsumedType = -1
        illusionSpecies = -1; illusionName = null
        if (powerTrick) {
            powerTrick = false
            stats = baseStats.copyOf()
        }
        ability = baseAbility
        mimicOriginal?.let { (i, slot) -> moves[i] = slot }
        mimicOriginal = null
        // Gen 5: the sleep counter restarts when the Pokémon switches out; Toxic goes back to its first stage.
        toxicCounter = 0
    }

    fun hasType(t: Int) = t in types && !(t == Types.FLYING && roosted)
    val effectiveTypes: List<Int> get() = if (roosted) types.filter { it != Types.FLYING }.ifEmpty { listOf(Types.NORMAL) } else types

    fun hpFraction() = hp.toFloat() / maxHp
}
