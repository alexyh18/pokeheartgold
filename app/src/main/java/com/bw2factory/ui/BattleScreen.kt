package com.bw2factory.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bw2factory.data.GameData
import com.bw2factory.engine.Action
import com.bw2factory.engine.BattleMon
import com.bw2factory.engine.Josa
import com.bw2factory.engine.Weather
import kotlin.math.sin

private const val SCENE_W = 256f
private const val SCENE_H = 160f

@Composable
fun BattleScreen(vm: GameViewModel, s: BattleSession) {
    Column(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { s.onTap() },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(SCENE_W / SCENE_H)) {
            val u = with(LocalDensity.current) { maxWidth.toPx() } / SCENE_W
            Scene(vm, s)
            val density = LocalDensity.current
            val dp = { v: Float -> with(density) { (v * u).toDp() } }
            HpBox(vm.data, s, 1, Modifier.offset(x = dp(4f), y = dp(6f)).width(dp(120f)))
            HpBox(vm.data, s, 0, Modifier.offset(x = dp(132f), y = dp(112f)).width(dp(120f)))
        }
        MessageBox(s.message)
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(Brush.verticalGradient(listOf(Color(0xFF283440), Color(0xFF18202A))))
                .padding(10.dp),
        ) {
            when (s.mode) {
                InputMode.COMMAND -> CommandMenu(s)
                InputMode.FIGHT -> FightMenu(vm, s)
                InputMode.PARTY, InputMode.REPLACE -> PartyMenu(vm, s)
                InputMode.NONE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    Text(if (s.fast) "▶▶ 빠르게" else "화면을 탭하면 진행", color = Bw.textDim, fontSize = 12.sp,
                        modifier = Modifier.clickable { s.fast = !s.fast }.padding(6.dp))
                }
            }
        }
    }
}

@Composable
private fun Scene(vm: GameViewModel, s: BattleSession) {
    val foe = s.views[1]
    val me = s.views[0]
    val foeBmp = foe.mon?.let { vm.sprites.front(it.species.id) }
    val myBmp = me.mon?.let { vm.sprites.back(it.species.id) }
    // read animated values so the canvas redraws
    val fe = foe.enter.value; val ff = foe.faint.value; val fs = foe.shake.value; val fl = foe.lunge.value
    val me1 = me.enter.value; val mf = me.faint.value; val ms = me.shake.value; val ml = me.lunge.value
    val weather = s.weather
    Canvas(Modifier.fillMaxSize()) {
        val u = size.width / SCENE_W
        // sky and field
        drawRect(Brush.verticalGradient(listOf(Color(0xFF68B0E8), Color(0xFFC8E8F8)), endY = 70 * u), size = Size(size.width, 70 * u))
        drawRect(Color(0xFF90B868), Offset(0f, 62 * u), Size(size.width, 10 * u))
        drawRect(Brush.verticalGradient(listOf(Color(0xFF80B858), Color(0xFF4E8C3C)), startY = 70 * u, endY = size.height),
            Offset(0f, 70 * u), Size(size.width, size.height - 70 * u))
        // platforms
        drawOval(Color(0xFF5C8E40), Offset((188 - 58) * u, (90 - 13) * u), Size(116 * u, 26 * u))
        drawOval(Color(0xFF9CCB70), Offset((188 - 52) * u, (90 - 11) * u), Size(104 * u, 20 * u))
        drawOval(Color(0xFF4A7E34), Offset((70 - 78) * u, (152 - 16) * u), Size(156 * u, 32 * u))
        drawOval(Color(0xFF8CC060), Offset((70 - 70) * u, (152 - 13) * u), Size(140 * u, 25 * u))
        // foe (front sprite, 96x96)
        if (foeBmp != null && (foe.visible || ff < 1f) && !foe.hidden) {
            val sz = 96 * u
            val x = (188 - 48) * u + sin(fs * Math.PI.toFloat()) * 4 * u - fl * 10 * u
            val y = (95 - 96) * u + ff * 40 * u
            sprite(foeBmp, x, y, sz, alpha = (if (foe.visible) fe else 1f) * (1f - ff), flash = foe.flashUp)
        }
        // player (back sprite, scaled up like BW's closer camera)
        if (myBmp != null && (me.visible || mf < 1f) && !me.hidden) {
            val sz = 132 * u
            val x = (70 - 66) * u + sin(ms * Math.PI.toFloat()) * 4 * u + ml * 12 * u
            val y = (160 - 132) * u + mf * 50 * u
            sprite(myBmp, x, y, sz, alpha = (if (me.visible) me1 else 1f) * (1f - mf), flash = me.flashUp)
        }
        // weather tint
        val tint = when (weather) {
            Weather.SUN -> Color(0x30FFC040)
            Weather.RAIN -> Color(0x383060C0)
            Weather.SAND -> Color(0x40B09050)
            Weather.HAIL -> Color(0x40E0F0FF)
            Weather.NONE -> null
        }
        if (tint != null) drawRect(tint, size = size)
    }
}

private fun DrawScope.sprite(bmp: ImageBitmap, x: Float, y: Float, sz: Float, alpha: Float, flash: Boolean?) {
    val a = alpha.coerceIn(0f, 1f)
    drawImage(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), IntOffset(x.toInt(), y.toInt()), IntSize(sz.toInt(), sz.toInt()),
        alpha = a, filterQuality = FilterQuality.None)
    if (flash != null) {
        drawImage(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), IntOffset(x.toInt(), y.toInt()), IntSize(sz.toInt(), sz.toInt()),
            alpha = 0.55f * a, filterQuality = FilterQuality.None,
            colorFilter = ColorFilter.tint(if (flash) Color(0xFFFF6040) else Color(0xFF4080FF), BlendMode.SrcIn))
    }
}

@Composable
private fun HpBox(data: GameData, s: BattleSession, side: Int, modifier: Modifier) {
    val v = s.views[side]
    val m = v.mon ?: return
    if (!v.visible && v.faint.value >= 1f) return
    @Suppress("UNUSED_VARIABLE") val tick = v.statusTick
    Column(
        modifier.clip(RoundedCornerShape(if (side == 1) 0.dp else 6.dp, 6.dp, 6.dp, if (side == 1) 6.dp else 0.dp))
            .background(Color(0xE0283038)).border(1.dp, Color(0xFF8090A0), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            genderMark(m)?.let { (t, c) -> Text(t, color = c, fontSize = 11.sp) }
            Text(" Lv${m.level}", color = Color.White, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(m.status)
            if (m.status.ordinal != 0) Spacer(Modifier.width(3.dp))
            HpBar(v.hpAnim.value, Modifier.weight(1f))
        }
        if (side == 0) {
            Text("${v.displayHp} / ${v.maxHp}", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                s.battle.sides[1].party.forEach { p ->
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (p.fainted) Color(0xFF606060) else Color(0xFFE84848)))
                }
            }
        }
    }
}

private fun genderMark(m: BattleMon) = when (m.gender) {
    0 -> "♂" to Color(0xFF68A8F8)
    1 -> "♀" to Color(0xFFF87878)
    else -> null
}

@Composable
private fun MessageBox(text: String) {
    Box(
        Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF202020))
            .border(3.dp, Color(0xFF58A0B8)).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun CommandMenu(s: BattleSession) {
    val m = s.player.mon
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BwButton("싸우다", Modifier.fillMaxWidth().weight(1.4f), color = Color(0xFFD83838)) { s.mode = InputMode.FIGHT }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BwButton("가방", Modifier.weight(1f).fillMaxHeight(), color = Color(0xFFD8A020), enabled = false) {}
            BwButton("포켓몬", Modifier.weight(1f).fillMaxHeight(), color = Color(0xFF38A848)) { s.mode = InputMode.PARTY }
        }
        Text(
            if (s.fast) "메시지 속도: 빠르게 (탭하여 변경)" else "메시지 속도: 보통 (탭하여 변경)",
            color = Bw.textDim, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.End).clickable { s.fast = !s.fast },
        )
        if (s.battle.usableSlots(m).isEmpty()) Text("쓸 수 있는 기술이 없다! 싸우다를 누르면 발버둥을 쓴다.", color = Bw.accent, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FightMenu(vm: GameViewModel, s: BattleSession) {
    val m = s.player.mon
    val usable = s.battle.usableSlots(m)
    val data = vm.data
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (usable.isEmpty()) {
            BwButton("발버둥", Modifier.fillMaxWidth().weight(1f)) { s.choose(Action.Fight(-1)) }
        } else {
            m.moves.chunked(2).forEachIndexed { r, row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, slot ->
                        val i = r * 2 + c
                        val ok = i in usable
                        val col = Bw.typeColor(s.battle.moveType(m, slot.move))
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                                .background(if (ok) Brush.verticalGradient(listOf(col, col.copy(red = col.red * 0.65f, green = col.green * 0.65f, blue = col.blue * 0.65f)))
                                else Brush.verticalGradient(listOf(Color(0xFF505860), Color(0xFF3C444C))))
                                .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    enabled = true,
                                    onClick = { if (ok) s.choose(Action.Fight(i)) else s.message = "${Josa.eunNeun(slot.move.nameKo)} 지금 쓸 수 없다!" },
                                    onLongClick = { s.message = slot.move.desc },
                                )
                                .padding(8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(slot.move.nameKo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                            Row {
                                Text(data.typeName(slot.move.type), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text("PP ${slot.pp}/${slot.maxPp}", color = if (slot.pp == 0) Bw.accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                when (slot.move.category) { "PHYSICAL" -> "물리"; "SPECIAL" -> "특수"; else -> "변화" } +
                                    (if (slot.move.power > 1) " · 위력 ${slot.move.power}" else "") +
                                    (if (slot.move.accuracy in 1..100) " · 명중 ${slot.move.accuracy}" else ""),
                                color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("길게 누르면 기술 설명", color = Bw.textDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            BwButton("돌아가기", Modifier.width(120.dp), color = Color(0xFF4878B8)) {
                s.mode = InputMode.COMMAND
                s.message = "${Josa.eunNeun(s.player.mon.name)} 무엇을 할까?"
            }
        }
    }
}

@Composable
private fun PartyMenu(vm: GameViewModel, s: BattleSession) {
    val side = s.player
    val forced = s.mode == InputMode.REPLACE
    val active = side.mon
    val canSwitch = forced || s.battle.canSwitch(active)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        side.party.forEachIndexed { i, p ->
            val isActive = i == side.active && !forced
            val enabled = !p.fainted && i != side.active && canSwitch
            BwPanel(
                Modifier.fillMaxWidth().weight(1f).clickable(enabled = enabled || (!forced && i != side.active && !p.fainted)) {
                    when {
                        !canSwitch -> s.message = "${Josa.eunNeun(active.name)} 교체할 수 없다!"
                        forced -> s.chooseReplacement(i)
                        else -> s.choose(Action.Switch(i))
                    }
                },
                selected = isActive,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonIcon(vm.sprites, p.species.id, 40)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, color = if (p.fainted) Bw.textDim else Bw.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            StatusBadge(p.status)
                            if (isActive) Text(" 싸우는 중", color = Bw.gold, fontSize = 11.sp)
                            if (p.fainted) Text(" 기절", color = Bw.accent, fontSize = 11.sp)
                        }
                        HpBar(p.hp.toFloat() / p.maxHp, Modifier.fillMaxWidth())
                        Text("${p.hp}/${p.maxHp} · ${vm.data.itemName(p.item)}", color = Bw.textDim, fontSize = 11.sp)
                    }
                }
            }
        }
        if (!forced) {
            BwButton("돌아가기", Modifier.fillMaxWidth(), color = Color(0xFF4878B8)) {
                s.mode = InputMode.COMMAND
            }
        }
    }
}
