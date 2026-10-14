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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
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
import com.bw2factory.engine.Stat
import com.bw2factory.engine.Weather
import kotlin.math.sin

private const val SCENE_W = 256f
private const val SCENE_H = 192f

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
            HpBox(vm.data, s, 1, Modifier.offset(x = dp(4f), y = dp(8f)).width(dp(120f)))
            HpBox(vm.data, s, 0, Modifier.offset(x = dp(132f), y = dp(140f)).width(dp(120f)))
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
    val bg = vm.sprites.battleBackground(s.background)
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val u = size.width / SCENE_W
        // Battle Subway stage background (250x198 rip, stretched to the 256x192 top screen)
        if (bg != null) drawPixel(bg, Offset.Zero, size)
        else drawRect(Brush.verticalGradient(listOf(Color(0xFF68B0E8), Color(0xFF4E8C3C))), size = size)
        // foe (front sprite, 96x96) stands on the far platform near (186, 98); slides in from the right
        if (foeBmp != null && (foe.visible || ff < 1f) && !foe.hidden) {
            val sz = 96 * u
            val slide = (1f - (if (foe.visible) fe else 1f)) * 60 * u
            val x = (186 - 48) * u + sin(fs * Math.PI.toFloat()) * 4 * u - fl * 10 * u + slide
            val y = (110 - 96) * u + ff * 40 * u
            val a = (if (foe.visible) fe else 1f) * (1f - ff)
            spriteShadow(x, y, sz, 0.86f, a)
            sprite(foeBmp, x, y, sz, alpha = a, flash = foe.flashUp)
        }
        // player (back sprite, scaled up like BW's closer camera) on the near platform; slides in from the left
        if (myBmp != null && (me.visible || mf < 1f) && !me.hidden) {
            val sz = 132 * u
            val slide = (1f - (if (me.visible) me1 else 1f)) * 70 * u
            val x = (78 - 66) * u + sin(ms * Math.PI.toFloat()) * 4 * u + ml * 12 * u - slide
            val y = (196 - 132) * u + mf * 50 * u
            val a = (if (me.visible) me1 else 1f) * (1f - mf)
            spriteShadow(x, y, sz, 0.9f, a)
            sprite(myBmp, x, y, sz, alpha = a, flash = me.flashUp)
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

/** Soft elliptical drop shadow under a sprite, anchored to the platform. */
private fun DrawScope.spriteShadow(x: Float, y: Float, sz: Float, foot: Float, alpha: Float) {
    if (alpha <= 0f) return
    val w = sz * 0.62f
    val h = sz * 0.14f
    drawOval(
        Color(0x33000000),
        topLeft = Offset(x + sz / 2 - w / 2, y + sz * foot - h / 2),
        size = Size(w, h),
        alpha = alpha,
    )
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

/** BW2-style status HUD box: dark slanted panel, name + gender + Lv, a BW HP bar, party dots / HP count. */
@Composable
private fun HpBox(data: GameData, s: BattleSession, side: Int, modifier: Modifier) {
    val v = s.views[side]
    val m = v.mon ?: return
    if (!v.visible && v.faint.value >= 1f) return
    @Suppress("UNUSED_VARIABLE") val tick = v.statusTick
    val shape = GenericShape { size, _ ->
        // parallelogram tilt like the BW2 HUD, slanted toward the screen edge
        val t = size.height * 0.5f
        if (side == 1) { moveTo(t, 0f); lineTo(size.width, 0f); lineTo(size.width - t, size.height); lineTo(0f, size.height) }
        else { moveTo(0f, 0f); lineTo(size.width - t, 0f); lineTo(size.width, size.height); lineTo(t, size.height) }
        close()
    }
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xF0384450), Color(0xF01C242C))))
            .border(2.dp, Color(0xFFE8E8F0), shape)
            .padding(start = if (side == 1) 16.dp else 10.dp, end = if (side == 1) 10.dp else 16.dp, top = 3.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            genderMark(m)?.let { (t, c) -> Text(t, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            if (m.status.ordinal != 0) { Spacer(Modifier.width(4.dp)); StatusBadge(m.status) }
            Spacer(Modifier.weight(1f))
            Text("Lv", color = Bw.gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("${m.level}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(1.dp))
        Bw2HpBar(v.hpAnim.value, Modifier.fillMaxWidth())
        if (side == 0) {
            Text("${v.displayHp} / ${v.maxHp}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End))
        } else {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                s.battle.sides[1].party.forEach { p ->
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50))
                        .background(if (p.fainted) Color(0xFF586068) else Color(0xFFF0C840))
                        .border(1.dp, Color(0xFF101418), RoundedCornerShape(50)))
                }
            }
        }
    }
}

/** The thin black-outlined HP bar with the BW2 green/yellow/red fill and a highlight line. */
@Composable
private fun Bw2HpBar(fraction: Float, modifier: Modifier = Modifier) {
    val c = when {
        fraction > 0.5f -> Color(0xFF40C850) to Color(0xFF80F088)
        fraction > 0.2f -> Color(0xFFF0C020) to Color(0xFFF8E060)
        else -> Color(0xFFE84030) to Color(0xFFF88070)
    }
    Row(modifier.height(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(16.dp).height(9.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFE8A020)),
            contentAlignment = Alignment.Center,
        ) { Text("HP", color = Color(0xFF203048), fontSize = 7.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(2.dp))
        Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF101820)).padding(1.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(1.dp)).background(c.first)) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(c.second))
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
    // BW2-style framed message window: rounded dark-blue panel with a light inner border.
    Box(Modifier.fillMaxWidth().height(66.dp).padding(4.dp)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF283848), Color(0xFF1A2430))))
                .border(2.dp, Color(0xFF88B0C8), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        }
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
    var detail by remember(s.mode) { mutableStateOf<Int?>(null) }

    detail?.let { idx ->
        MonSummary(vm, s, idx, canSwitch, forced,
            onBack = { detail = null },
            onSwitch = {
                when {
                    forced -> s.chooseReplacement(idx)
                    else -> s.choose(Action.Switch(idx))
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("포켓몬을 탭하면 능력치와 기술을 봅니다.", color = Bw.textDim, fontSize = 11.sp)
        side.party.forEachIndexed { i, p ->
            val isActive = i == side.active && !forced
            BwPanel(
                Modifier.fillMaxWidth().weight(1f).clickable { detail = i },
                selected = isActive,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonIcon(vm.sprites, p.species.id, 40)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, color = if (p.fainted) Bw.textDim else Bw.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.width(4.dp))
                            genderMark(p)?.let { (t, c) -> Text(t, color = c, fontSize = 12.sp) }
                            Spacer(Modifier.weight(1f))
                            StatusBadge(p.status)
                            if (isActive) Text(" 싸우는 중", color = Bw.gold, fontSize = 11.sp)
                            if (p.fainted) Text(" 기절", color = Bw.accent, fontSize = 11.sp)
                        }
                        HpBar(p.hp.toFloat() / p.maxHp, Modifier.fillMaxWidth())
                        Text("${p.hp}/${p.maxHp} · ${vm.data.itemName(p.item)} · ›", color = Bw.textDim, fontSize = 11.sp)
                    }
                }
            }
        }
        if (!forced) {
            BwButton("돌아가기", Modifier.fillMaxWidth(), color = Color(0xFF4878B8)) { s.mode = InputMode.COMMAND }
        }
    }
}

/** In-battle summary: base stats with current stage arrows, and the four moves with type/PP/power. */
@Composable
private fun MonSummary(vm: GameViewModel, s: BattleSession, idx: Int, canSwitch: Boolean, forced: Boolean, onBack: () -> Unit, onSwitch: () -> Unit) {
    val m = s.player.party[idx]
    val data = vm.data
    val isActive = idx == s.player.active && !forced
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BwPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(vm.sprites.front(m.species.id), Modifier.size(56.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, color = Bw.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.width(4.dp))
                        genderMark(m)?.let { (t, c) -> Text(t, color = c, fontSize = 14.sp) }
                        Spacer(Modifier.width(6.dp))
                        Text("Lv${m.level}", color = Bw.textDim, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { m.species.types.forEach { TypeBadge(data, it) } }
                    Text("특성 ${data.abilityName(m.ability)}", color = Bw.textDim, fontSize = 12.sp)
                    Text("도구 ${data.itemName(m.item)}", color = Bw.textDim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            HpBar(m.hp.toFloat() / m.maxHp, Modifier.fillMaxWidth())
            Text("HP ${m.hp} / ${m.maxHp}", color = Bw.text, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))
        }
        // stats with stage arrows (current battle stat vs base, so held-item/ability effects show)
        BwPanel(Modifier.fillMaxWidth()) {
            Text("능력치", color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            val labels = listOf("공격" to Stat.ATK, "방어" to Stat.DEF, "특공" to Stat.SPA, "특방" to Stat.SPD, "스피드" to Stat.SPE)
            labels.forEach { (name, stat) ->
                val stage = m.stages[stat]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Bw.textDim, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    Text("${m.stats[stat]}", color = Bw.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                    if (stage != 0) {
                        Text((if (stage > 0) "▲" else "▼").repeat(minOf(3, kotlin.math.abs(stage))) + if (stage > 0) " +$stage" else " $stage",
                            color = if (stage > 0) Bw.hpGreen else Bw.accent, fontSize = 11.sp)
                    }
                }
            }
            val nat = data.natures.getOrNull(m.info.set.nature)?.nameKo ?: ""
            Text("성격 $nat · 개체값 ${m.info.mon.iv}", color = Bw.textDim, fontSize = 11.sp)
        }
        // moves
        BwPanel(Modifier.fillMaxWidth()) {
            Text("기술", color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            m.moves.forEach { slot ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Bw.typeColor(slot.move.type)).width(44.dp).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                        Text(data.typeName(slot.move.type), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(slot.move.nameKo, color = Bw.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when (slot.move.category) { "PHYSICAL" -> "물리"; "SPECIAL" -> "특수"; else -> "변화" } +
                                (if (slot.move.power > 1) " · 위력 ${slot.move.power}" else "") +
                                (if (slot.move.accuracy in 1..100) " · 명중 ${slot.move.accuracy}" else " · 명중 -"),
                            color = Bw.textDim, fontSize = 10.sp,
                        )
                    }
                    Text("PP ${slot.pp}/${slot.maxPp}", color = if (slot.pp == 0) Bw.accent else Bw.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BwButton("뒤로", Modifier.weight(1f), color = Color(0xFF4878B8), onClick = onBack)
            val switchEnabled = !m.fainted && !isActive && canSwitch
            BwButton(if (isActive) "싸우는 중" else "교체", Modifier.weight(1f), enabled = switchEnabled, onClick = onSwitch)
        }
    }
}
