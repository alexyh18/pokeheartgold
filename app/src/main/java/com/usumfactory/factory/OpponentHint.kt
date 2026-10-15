package com.usumfactory.factory

import com.usumfactory.data.GameData
import com.usumfactory.data.MoveData
import com.usumfactory.engine.FactoryMon

/**
 * What the player is told about the next opponent before choosing Pokémon:
 *  - round 1: all three Pokémon
 *  - round 2: two Pokémon (the lead and the second)
 *  - round 3: the lead and one of its moves
 *  - round 4+: the type the trainer mainly uses
 */
sealed interface OpponentHint {
    data class Species(val species: List<Int>) : OpponentHint
    data class LeadAndMove(val species: Int, val move: MoveData) : OpponentHint
    data class MainType(val type: Int) : OpponentHint

    companion object {
        fun of(data: GameData, round: Int, opponent: List<FactoryMon>): OpponentHint? {
            if (opponent.isEmpty()) return null
            val sets = opponent.map { data.sets.getValue(it.setId) }
            return when (round) {
                0 -> Species(sets.map { it.species })
                1 -> Species(sets.take(2).map { it.species })
                2 -> {
                    val lead = sets[0]
                    // Fixed per Pokémon (from its PID) so the hint doesn't change between screens.
                    val move = data.move(lead.moves[(opponent[0].pid % lead.moves.size).toInt()])
                    LeadAndMove(lead.species, move)
                }
                else -> MainType(mainType(data, sets.map { it.species }, sets.flatMap { it.moves }))
            }
        }

        /**
         * Most common type among the three Pokémon's types; ties go to the type of more of their attacking
         * moves, then to the lowest type id.
         */
        private fun mainType(data: GameData, species: List<Int>, moves: List<Int>): Int {
            val byMon = IntArray(17)
            for (sp in species) data.species.getValue(sp).types.forEach { if (it < 17) byMon[it]++ }
            val byMove = IntArray(17)
            for (id in moves) {
                val m = data.move(id)
                if (!m.isStatus && m.type < 17) byMove[m.type]++
            }
            return (0 until 17).maxWith(compareBy<Int> { byMon[it] }.thenBy { byMove[it] }.thenByDescending { it })
        }
    }
}
