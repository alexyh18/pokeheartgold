package com.usumfactory.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Species data from pokebw2 data/pokemon/<species>/data.json (Gen 5 base stats/abilities). */
@Serializable
data class SpeciesData(
    val id: Int,
    val name: String,
    val nameKo: String,
    val types: List<Int>,
    /** HP, Atk, Def, Spe, SpA, SpD */
    val base: List<Int>,
    val abilities: List<Int>,
    /** in 0.1 kg */
    val weight: Int,
    val genderRatio: Int,
    /** true if this species cannot evolve further (fully evolved, or a single-stage species) */
    val final: Boolean = true,
)

@Serializable
data class StatChangeData(val stat: String, val stages: Int, val chance: Int)

/** Move data from pokebw2 data/moves/<move>/data.json. Accuracy 101 means it never misses. */
@Serializable
data class MoveData(
    val id: Int,
    val key: String,
    val name: String,
    val nameKo: String,
    val desc: String,
    val type: Int,
    val category: String,
    val power: Int,
    val accuracy: Int,
    val pp: Int,
    val priority: Int,
    val hitsMin: Int,
    val hitsMax: Int,
    val inflict: String,
    val inflictChance: Int,
    val crit: Int,
    val flinch: Int,
    val effect: String,
    val drain: Int,
    val heal: Int,
    val target: String,
    val stats: List<StatChangeData>,
    /** True when a damaging move's [stats] changes apply to the USER (Charge Beam, Overheat, Close
     *  Combat, …) rather than the target. Defaults to false (target-affecting). */
    val statsSelf: Boolean = false,
    val flags: List<String>,
) {
    val isPhysical get() = category == "PHYSICAL"
    val isSpecial get() = category == "SPECIAL"
    val isStatus get() = category == "STATUS"
    fun has(flag: String) = flag in flags
    val targetsSelf get() = target == "USER" || target == "USER_SIDE" || target == "USER_OR_ALLY"
    // A damaging "ALL"/"ALL_FOES" move is a spread attack (it still hits the foe in a single battle);
    // only a STATUS move with these targets is a true field/side effect (Haze, weather, terrain…).
    val targetsField get() = target == "FIELD" || target == "FOE_SIDE" ||
        ((target == "ALL" || target == "ALL_FOES") && isStatus)
}

@Serializable
data class ItemData(
    val id: Int,
    val key: String,
    val name: String,
    val nameKo: String,
    val desc: String,
    val holdEffect: String,
    val holdParam: Int,
    val flingPower: Int,
)

@Serializable
data class AbilityData(val id: Int, val key: String, val name: String, val nameKo: String, val desc: String)

@Serializable
data class NatureData(val id: Int, val key: String, val nameKo: String)

/** A Mega Evolution forme, triggered by holding [stone]. base = HP,Atk,Def,Spe,SpA,SpD. */
@Serializable
data class MegaForme(
    val species: Int,
    val stone: Int,
    val nameKo: String,
    val types: List<Int>,
    val base: List<Int>,
    val ability: Int,
    /** Sprite id to draw while mega-evolved (its own PokeAPI form id); falls back to [species]. */
    val sprite: Int = 0,
)

/** An HP-triggered battle forme (Darmanitan's Zen Mode). base = HP,Atk,Def,Spe,SpA,SpD. */
@Serializable
data class ZenForme(
    val species: Int,
    val nameKo: String,
    val types: List<Int>,
    val base: List<Int>,
    val sprite: Int = 0,
)

/** Korean display name for a type's damaging Z-move (Gen 7), keyed by engine type id. */
@Serializable
data class ZMoveName(val type: Int, val nameKo: String)

@Serializable
data class TypeData(val id: Int, val key: String, val nameKo: String)

/** A Battle Factory set from HGSS files/a/2/0/3. evs: HP, Atk, Def, Spe, SpA, SpD flags. */
@Serializable
data class FactorySet(
    val id: Int,
    val species: Int,
    val moves: List<Int>,
    val evs: List<Boolean>,
    val nature: Int,
    val item: Int,
    val form: Int,
    /** true if this set is used by a Super Single trainer (the Super course pool) */
    @SerialName("super") val superCourse: Boolean = true,
)

/** A frontier trainer from HGSS files/a/2/0/2 (name from msg_0027, class from msg_0730). */
@Serializable
data class TrainerData(
    val id: Int,
    val trainerClass: Int,
    val classKey: String = "",
    val className: String,
    val classKo: String = "",
    val name: String,
    val sprite: Int = 0,
    /** set ids (into GameData.sets) this trainer may use */
    val sets: List<Int> = emptyList(),
    /** Battle Tree streak range in which this trainer can appear (inclusive, 1-based battle numbers). */
    val start: Int = 1,
    val end: Int = 700,
)

class GameData(
    species: List<SpeciesData>,
    moves: List<MoveData>,
    items: List<ItemData>,
    abilities: List<AbilityData>,
    val natures: List<NatureData>,
    val types: List<TypeData>,
    sets: List<FactorySet>,
    val trainers: List<TrainerData>,
    megas: List<MegaForme> = emptyList(),
    zenFormes: List<ZenForme> = emptyList(),
    zMoveNames: List<ZMoveName> = emptyList(),
) {
    val species = species.associateBy { it.id }
    /** Mega formes keyed by the Mega Stone item id that triggers them. */
    val megasByStone = megas.associateBy { it.stone }
    /** HP-triggered formes (Zen Mode) keyed by base species id. */
    val zenBySpecies = zenFormes.associateBy { it.species }
    /** Damaging Z-move display name keyed by engine type id (Gen 7 type-based Z-moves). */
    val zMoveNameByType = zMoveNames.associate { it.type to it.nameKo }
    val moves = moves.associateBy { it.id }
    val movesByKey = moves.associateBy { it.key }
    val items = items.associateBy { it.id }
    val abilities = abilities.associateBy { it.id }
    val sets = sets.associateBy { it.id }

    /** Every Battle Tree set is eligible for the pool (the `super` flag marks "in the pool"). */
    val superSetIds: List<Int> = sets.filter { it.species != 0 && it.superCourse }.map { it.id }

    /** Eligible sets that hold a Mega Stone (a Mega Evolution forme exists for the held item). */
    val megaStoneSetIds: List<Int> = superSetIds.filter { id -> megasByStone.containsKey(this.sets.getValue(id).item) }

    fun isEligible(setId: Int): Boolean {
        val s = sets[setId] ?: return false
        return s.species != 0 && s.superCourse
    }

    fun move(id: Int) = moves.getValue(id)
    fun move(key: String) = movesByKey.getValue(key)
    fun typeName(t: Int) = types.getOrNull(t)?.nameKo ?: "???"
    fun abilityName(a: Int) = abilities[a]?.nameKo ?: "-"
    fun itemName(i: Int) = items[i]?.nameKo ?: "-"

    /** Struggle isn't in any Factory set, so it's defined here (BW2 data/moves/struggle). */
    val struggle = MoveData(
        id = 165, key = "struggle", name = "Struggle", nameKo = "발버둥", desc = "",
        type = TYPE_NULL, category = "PHYSICAL", power = 50, accuracy = 101, pp = 1, priority = 0,
        hitsMin = 0, hitsMax = 0, inflict = "NONE", inflictChance = 0, crit = 0, flinch = 0,
        effect = "STRUGGLE", drain = 0, heal = 0, target = "RANDOM_FOE", stats = emptyList(),
        flags = listOf("CONTACT", "PROTECT"),
    )

    companion object {
        const val TYPE_NULL = 18
        private val json = Json { ignoreUnknownKeys = true }

        fun load(read: (String) -> String) = GameData(
            json.decodeFromString(read("data/species.json")),
            json.decodeFromString(read("data/moves.json")),
            json.decodeFromString(read("data/items.json")),
            json.decodeFromString(read("data/abilities.json")),
            json.decodeFromString(read("data/natures.json")),
            json.decodeFromString(read("data/types.json")),
            json.decodeFromString(read("data/sets.json")),
            json.decodeFromString(read("data/trainers.json")),
            runCatching { json.decodeFromString<List<MegaForme>>(read("data/megas.json")) }.getOrDefault(emptyList()),
            runCatching { json.decodeFromString<List<ZenForme>>(read("data/zen.json")) }.getOrDefault(emptyList()),
            runCatching { json.decodeFromString<List<ZMoveName>>(read("data/zmoves.json")) }.getOrDefault(emptyList()),
        )
    }
}
