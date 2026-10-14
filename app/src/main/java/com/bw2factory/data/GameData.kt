package com.bw2factory.data

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
    val flags: List<String>,
) {
    val isPhysical get() = category == "PHYSICAL"
    val isSpecial get() = category == "SPECIAL"
    val isStatus get() = category == "STATUS"
    fun has(flag: String) = flag in flags
    val targetsSelf get() = target == "USER" || target == "USER_SIDE" || target == "USER_OR_ALLY"
    val targetsField get() = target == "FIELD" || target == "FOE_SIDE" || target == "ALL"
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
) {
    val species = species.associateBy { it.id }
    val moves = moves.associateBy { it.id }
    val movesByKey = moves.associateBy { it.key }
    val items = items.associateBy { it.id }
    val abilities = abilities.associateBy { it.id }
    val sets = sets.associateBy { it.id }

    /** Set ids whose species is fully evolved (used for the "final evolutions only" pools). */
    val finalEvoSetIds: List<Int> = sets.filter { it.species != 0 && species.first { s -> s.id == it.species }.final }.map { it.id }

    fun isFinalEvo(setId: Int): Boolean {
        val sp = sets[setId]?.species ?: return false
        return sp != 0 && (this.species[sp]?.final ?: false)
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
        const val TYPE_NULL = 17
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
        )
    }
}
