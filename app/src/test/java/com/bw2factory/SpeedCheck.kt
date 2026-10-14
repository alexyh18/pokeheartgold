package com.bw2factory
import com.bw2factory.data.GameData
import com.bw2factory.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class SpeedCheck {
    private val data = GameData.load { File("src/main/assets/$it").readText() }
    private fun mon(id: Int, side: Int) = BattleMon(data, MonInfo(data, FactoryMon(id, 31, 0x111, 100)), side)
    private fun noop() = object : BattleController { override suspend fun event(e: BattleEvent){}; override suspend fun chooseAction(b: Battle,s:Int)=Action.Fight(-1); override suspend fun chooseReplacement(b: Battle,s:Int)=0 }

    @Test fun speedStageScales() {
        val m = mon(data.superSetIds.first(), 0)
        val b = Battle(data, arrayOf(Side(0, listOf(m), "a"), Side(1, listOf(mon(data.superSetIds.first(), 1)), "b")), arrayOf(noop(), noop()), BattleRandom(1))
        val base = b.speed(m)
        m.stages[Stat.SPE] = 2            // +2 => x2.0
        assertEquals(base * 2, b.speed(m))
        m.stages[Stat.SPE] = 1            // +1 => x1.5
        assertEquals(base * 3 / 2, b.speed(m))
        m.stages[Stat.SPE] = -2           // -2 => x0.5
        assertEquals(base * 2 / 4, b.speed(m))
        println("speed +2=${base*2} +1=${base*3/2} base=$base -2=${base/2}")
    }

    @Test fun stageDecidesTurnOrder() {
        // Pick two sets where A is slower than B at base, then give A +2 Speed and confirm A outspeeds B.
        val sets = data.superSetIds
        var aId = -1; var bId = -1
        outer@ for (x in sets) for (y in sets) {
            val sa = MonInfo(data, FactoryMon(x,31,1,100)).stats[Stat.SPE]
            val sb = MonInfo(data, FactoryMon(y,31,1,100)).stats[Stat.SPE]
            if (sa in (sb*2/3)..(sb-1)) { aId = x; bId = y; break@outer }  // A clearly slower but within x2 reach
        }
        assertTrue(aId >= 0)
        val a = mon(aId, 0); val bm = mon(bId, 1)
        val battle = Battle(data, arrayOf(Side(0, listOf(a), "a"), Side(1, listOf(bm), "b")), arrayOf(noop(), noop()), BattleRandom(1))
        assertTrue("A base slower", battle.speed(a) < battle.speed(bm))
        a.stages[Stat.SPE] = 2
        assertTrue("A should outspeed B after +2", battle.speed(a) > battle.speed(bm))
        println("turn order: A ${battle.speed(a)} > B ${battle.speed(bm)} after +2 (base A slower)")
    }
}
