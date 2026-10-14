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
import androidx.compose.ui.geometry.CornerRadius
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
    // Continuous idle-bob clock: advance each visible Pokémon's phase ~60fps.
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            for (v in s.views) if (v.visible) v.idle = (v.idle + dt * 0.6f) % 1f
        }
    }
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
            HpBox(vm.data, s, 1, Modifier.offset(x = dp(4f), y = dp(8f)).width(dp(128f)))
            HpBox(vm.data, s, 0, Modifier.offset(x = dp(124f), y = dp(140f)).width(dp(128f)))
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
                InputMode.INFO -> FieldInfo(vm, s)
                InputMode.NONE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    Text("진행 속도: ${s.speedLabel} (탭하여 변경) · 화면을 탭하면 진행", color = Bw.textDim, fontSize = 12.sp,
                        modifier = Modifier.clickable { s.cycleSpeed() }.padding(6.dp))
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
        // gentle idle bob (vertical) so the Pokémon don't look frozen
        val foeBob = sin(foe.idle * 2f * Math.PI.toFloat()) * 2.5f * u
        val myBob = sin((me.idle + 0.5f) * 2f * Math.PI.toFloat()) * 3f * u
        // foe (front sprite, 96x96) stands on the far platform near (186, 98); slides in from the right
        if (foeBmp != null && (foe.visible || ff < 1f) && !foe.hidden) {
            val sz = 96 * u
            val slide = (1f - (if (foe.visible) fe else 1f)) * 60 * u
            val x = (186 - 48) * u + sin(fs * Math.PI.toFloat()) * 4 * u - fl * 10 * u + slide
            val y = (110 - 96) * u + ff * 40 * u + (if (ff == 0f) foeBob else 0f)
            val a = (if (foe.visible) fe else 1f) * (1f - ff)
            val subP = foe.subAnim.value
            spriteShadow(x, (110 - 96) * u + ff * 40 * u, sz, 0.86f, a)
            // Show the doll while the substitute is up, the Pokémon otherwise. Cross-fade on the swap.
            if (subP < 1f) sprite(foeBmp, x, y, sz, alpha = a * (1f - subP), flash = foe.flashUp)
            if (subP > 0f) drawSubDoll(x + sz / 2, (110) * u, sz * 0.5f * subP, a)
        }
        // player (back sprite, scaled up like BW's closer camera) on the near platform; slides in from the left
        if (myBmp != null && (me.visible || mf < 1f) && !me.hidden) {
            val sz = 132 * u
            val slide = (1f - (if (me.visible) me1 else 1f)) * 70 * u
            val x = (78 - 66) * u + sin(ms * Math.PI.toFloat()) * 4 * u + ml * 12 * u - slide
            val y = (196 - 132) * u + mf * 50 * u + (if (mf == 0f) myBob else 0f)
            val a = (if (me.visible) me1 else 1f) * (1f - mf)
            val subP = me.subAnim.value
            spriteShadow(x, (196 - 132) * u + mf * 50 * u, sz, 0.9f, a)
            if (subP < 1f) sprite(myBmp, x, y, sz, alpha = a * (1f - subP), flash = me.flashUp)
            if (subP > 0f) drawSubDoll(x + sz / 2, (188) * u, sz * 0.42f * subP, a)
        }
        // move effect overlay (procedural): travels from the user toward the target
        s.effect?.let { eff ->
            val p = eff.anim.value
            val src = if (eff.from == 1) Offset((186) * u, (62) * u) else Offset((78) * u, (150) * u)
            val dst = if (eff.to == 1) Offset((186) * u, (56) * u) else Offset((78) * u, (150) * u)
            drawMoveEffect(eff.type, eff.category, p, src, dst, u)
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

/**
 * Original, procedurally-drawn move effect (not from any game art).
 * Physical: an impact burst of streaks at the target. Special: a projectile with a trail flying src->dst.
 * Status: a ring of sparkles around the target. Colored by the move's type.
 */
private fun DrawScope.drawMoveEffect(type: Int, category: String, p: Float, src: Offset, dst: Offset, u: Float) {
    if (p <= 0f || p >= 1f) return
    val col = Bw.typeColor(type)
    when (category) {
        "SPECIAL" -> {
            val pos = Offset(src.x + (dst.x - src.x) * p, src.y + (dst.y - src.y) * p)
            // trailing orbs
            for (k in 0..4) {
                val tp = (p - k * 0.06f).coerceAtLeast(0f)
                val tpos = Offset(src.x + (dst.x - src.x) * tp, src.y + (dst.y - src.y) * tp)
                drawCircle(col.copy(alpha = (0.5f - k * 0.09f).coerceAtLeast(0f)), (7f - k) * u, tpos)
            }
            drawCircle(Color.White.copy(alpha = 0.9f), 4f * u, pos)
            drawCircle(col, 8f * u, pos, alpha = 0.8f)
        }
        "PHYSICAL" -> {
            // impact only in the second half, as the hit lands
            if (p < 0.45f) return
            val k = (p - 0.45f) / 0.55f
            val r = (6f + k * 20f) * u
            val a = (1f - k) * 0.85f
            for (i in 0 until 8) {
                val ang = i * (Math.PI.toFloat() / 4f) + k
                val len = r * (0.7f + 0.3f * sin((i * 1.3f)))
                drawLine(col.copy(alpha = a), dst, Offset(dst.x + cosf(ang) * len, dst.y + sinf(ang) * len), 3f * u)
            }
            drawCircle(Color.White.copy(alpha = a * 0.6f), 4f * u, dst)
        }
        else -> { // status: sparkle ring around the target
            val a = (1f - kotlin.math.abs(p - 0.5f) * 2f).coerceIn(0f, 1f)
            for (i in 0 until 6) {
                val ang = i * (Math.PI.toFloat() / 3f) + p * 6f
                val rr = 16f * u
                drawCircle(col.copy(alpha = a * 0.8f), 2.5f * u, Offset(dst.x + cosf(ang) * rr, dst.y + sinf(ang) * rr))
            }
        }
    }
}

private fun cosf(a: Float) = kotlin.math.cos(a)
private fun sinf(a: Float) = kotlin.math.sin(a)

/**
 * An original stand-in doll drawn for the Substitute move (not any game's substitute art):
 * a simple round-headed burlap puppet on a stick. [cx] is the horizontal centre, [footY] the ground line,
 * [h] the doll height, scaled by [alpha].
 */
private fun DrawScope.drawSubDoll(cx: Float, footY: Float, h: Float, alpha: Float) {
    if (h <= 0f || alpha <= 0f) return
    val tan = Color(0xFFD9B271)
    val tanDark = Color(0xFFB98F4E)
    val headR = h * 0.26f
    val bodyW = h * 0.42f
    val bodyTop = footY - h * 0.62f
    val bodyH = h * 0.5f
    // stick base
    drawLine(Color(0xFF8A6A3A).copy(alpha = alpha), Offset(cx, footY), Offset(cx, bodyTop + bodyH * 0.4f), h * 0.07f)
    // body (rounded sack)
    drawRoundRect(
        tan.copy(alpha = alpha),
        topLeft = Offset(cx - bodyW / 2, bodyTop),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(bodyW * 0.4f, bodyW * 0.4f),
    )
    drawRoundRect(
        tanDark.copy(alpha = alpha),
        topLeft = Offset(cx - bodyW / 2, bodyTop + bodyH * 0.6f),
        size = Size(bodyW, bodyH * 0.4f),
        cornerRadius = CornerRadius(bodyW * 0.4f, bodyW * 0.4f),
    )
    // head
    val headCy = bodyTop - headR * 0.7f
    drawCircle(tan.copy(alpha = alpha), headR, Offset(cx, headCy))
    // tie between head and body
    drawRect(tanDark.copy(alpha = alpha), topLeft = Offset(cx - headR * 0.5f, headCy + headR * 0.7f), size = Size(headR, headR * 0.5f))
    // simple stitched eyes + mouth
    val eye = Color(0xFF2A2018).copy(alpha = alpha)
    drawCircle(eye, headR * 0.14f, Offset(cx - headR * 0.4f, headCy))
    drawCircle(eye, headR * 0.14f, Offset(cx + headR * 0.4f, headCy))
    drawLine(eye, Offset(cx - headR * 0.3f, headCy + headR * 0.45f), Offset(cx + headR * 0.3f, headCy + headR * 0.45f), h * 0.03f)
    // little stub arms
    drawRoundRect(tan.copy(alpha = alpha), Offset(cx - bodyW * 0.78f, bodyTop + bodyH * 0.1f), Size(bodyW * 0.3f, bodyH * 0.28f), CornerRadius(bodyW * 0.15f))
    drawRoundRect(tan.copy(alpha = alpha), Offset(cx + bodyW * 0.48f, bodyTop + bodyH * 0.1f), Size(bodyW * 0.3f, bodyH * 0.28f), CornerRadius(bodyW * 0.15f))
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
    // Gentle BW2-style slant. The slant eats `t` px of width on the leading edge, so pad the content
    // past it (and give the box enough width) to keep "Lv100" and the HP count from being clipped.
    val shape = GenericShape { size, _ ->
        val t = size.height * 0.28f
        if (side == 1) { moveTo(t, 0f); lineTo(size.width, 0f); lineTo(size.width - t, size.height); lineTo(0f, size.height) }
        else { moveTo(0f, 0f); lineTo(size.width - t, 0f); lineTo(size.width, size.height); lineTo(t, size.height) }
        close()
    }
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xF0384450), Color(0xF01C242C))))
            .border(2.dp, Color(0xFFE8E8F0), shape)
            .padding(start = if (side == 1) 18.dp else 12.dp, end = if (side == 1) 12.dp else 18.dp, top = 3.dp, bottom = 4.dp),
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

/** Field state (weather, rooms, hazards, screens) and both active Pokémon's current stat-stage changes. */
@Composable
private fun FieldInfo(vm: GameViewModel, s: BattleSession) {
    val b = s.battle
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BwPanel(Modifier.fillMaxWidth()) {
            Text("필드 상태", color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            val field = buildList {
                when (b.weather) {
                    Weather.SUN -> add("쾌청" to b.weatherTurns)
                    Weather.RAIN -> add("비" to b.weatherTurns)
                    Weather.SAND -> add("모래바람" to b.weatherTurns)
                    Weather.HAIL -> add("싸라기눈" to b.weatherTurns)
                    Weather.NONE -> {}
                }
                if (b.trickRoom > 0) add("트릭룸" to b.trickRoom)
                if (b.gravityTurns > 0) add("중력" to b.gravityTurns)
            }
            if (field.isEmpty()) Text("특별한 날씨/공간 효과 없음", color = Bw.textDim, fontSize = 12.sp)
            else field.forEach { (n, t) -> Text("$n  (남은 턴 ${if (t > 0) t else "-"})", color = Bw.text, fontSize = 13.sp) }
        }
        listOf(0 to "내 쪽", 1 to "상대 쪽").forEach { (side, label) ->
            val sd = b.sides[side]
            BwPanel(Modifier.fillMaxWidth()) {
                Text(label, color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                val eff = buildList {
                    if (sd.reflect > 0) add("리플렉터 ${sd.reflect}")
                    if (sd.lightScreen > 0) add("빛의장막 ${sd.lightScreen}")
                    if (sd.tailwind > 0) add("순풍 ${sd.tailwind}")
                    if (sd.safeguard > 0) add("신비의부적 ${sd.safeguard}")
                    if (sd.mist > 0) add("흰안개 ${sd.mist}")
                    if (sd.spikes > 0) add("압정뿌리기 ${sd.spikes}겹")
                    if (sd.toxicSpikes > 0) add("독압정 ${sd.toxicSpikes}겹")
                    if (sd.stealthRock) add("스텔스록")
                }
                if (eff.isEmpty()) Text("설치기/벽 효과 없음", color = Bw.textDim, fontSize = 12.sp)
                else Text(eff.joinToString(" · "), color = Bw.text, fontSize = 12.sp)
                val m = sd.mon
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonIcon(vm.sprites, m.species.id, 28)
                    Spacer(Modifier.width(4.dp))
                    Text(m.name, color = Bw.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    StatusBadge(m.status)
                }
                val labels = listOf("공격" to Stat.ATK, "방어" to Stat.DEF, "특공" to Stat.SPA, "특방" to Stat.SPD, "스피드" to Stat.SPE, "명중" to Stat.ACC, "회피" to Stat.EVA)
                val changed = labels.filter { m.stages[it.second] != 0 }
                if (changed.isEmpty()) Text("랭크 변화 없음", color = Bw.textDim, fontSize = 11.sp)
                else FlowStages(changed.map { it.first to m.stages[it.second] })
            }
        }
        BwButton("돌아가기", Modifier.fillMaxWidth(), color = Color(0xFF4878B8)) { s.mode = InputMode.COMMAND }
    }
}

@Composable
private fun FlowStages(items: List<Pair<String, Int>>) {
    Column {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, stage) ->
                    val up = stage > 0
                    Text("$name ${if (up) "+" else ""}$stage", color = if (up) Bw.hpGreen else Bw.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** (label, background color) for a move-effectiveness tier, or null when it should not be shown. */
private fun effTierLabel(tier: Int?): Pair<String, Color>? = when (tier) {
    null -> "효과 없음" to Color(0xFF606870)
    2 -> "효과가 매우 굉장" to Color(0xFF1E8A2E)
    1 -> "효과가 굉장" to Color(0xFF2EA84A)
    -1 -> "효과가 별로" to Color(0xFFB06030)
    -2 -> "효과가 매우 별로" to Color(0xFF9A3A2A)
    else -> null // x1 or status: no label
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
            BwButton("포켓몬", Modifier.weight(1f).fillMaxHeight(), color = Color(0xFF38A848)) { s.mode = InputMode.PARTY }
            BwButton("필드 정보", Modifier.weight(1f).fillMaxHeight(), color = Color(0xFF4878B8)) { s.mode = InputMode.INFO }
        }
        Text(
            "진행 속도: ${s.speedLabel} (탭하여 변경)",
            color = Bw.textDim, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.End).clickable { s.cycleSpeed() },
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
                            Text(slot.move.nameKo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(data.typeName(slot.move.type), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text("PP ${slot.pp}/${slot.maxPp}", color = if (slot.pp == 0) Bw.accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                when (slot.move.category) { "PHYSICAL" -> "물리"; "SPECIAL" -> "특수"; else -> "변화" } +
                                    (if (slot.move.power > 1) " · 위력 ${slot.move.power}" else "") +
                                    (if (slot.move.accuracy in 1..100) " · 명중 ${slot.move.accuracy}" else ""),
                                color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1,
                            )
                            // effectiveness vs. the current foe
                            effTierLabel(s.battle.moveEffectivenessTier(m, slot.move))?.let { (txt, bg) ->
                                Spacer(Modifier.height(2.dp))
                                Box(Modifier.clip(RoundedCornerShape(3.dp)).background(bg).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                    Text(txt, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
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
