package com.bw2factory.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bw2factory.data.GameData
import com.bw2factory.engine.MonInfo
import com.bw2factory.engine.Stat
import com.bw2factory.engine.Status

object Bw {
    val bgDark = Color(0xFF202830)
    val bgPanel = Color(0xFF303C48)
    val panelLine = Color(0xFF586878)
    val text = Color(0xFFF8F8F8)
    val textDim = Color(0xFFB0BCC8)
    val accent = Color(0xFFE84848)
    val gold = Color(0xFFF8C848)
    val hpGreen = Color(0xFF38D848)
    val hpYellow = Color(0xFFF8C800)
    val hpRed = Color(0xFFF83820)

    private val TYPE_COLORS = longArrayOf(
        0xFFA8A878, 0xFFC03028, 0xFFA890F0, 0xFFA040A0, 0xFFE0C068, 0xFFB8A038, 0xFFA8B820, 0xFF705898,
        0xFFB8B8D0, 0xFFF08030, 0xFF6890F0, 0xFF78C850, 0xFFF8D030, 0xFFF85888, 0xFF98D8D8, 0xFF7038F8,
        0xFF705848, 0xFF68A090,
    )

    fun typeColor(t: Int) = Color(TYPE_COLORS[t.coerceIn(0, TYPE_COLORS.size - 1)])
}

@Composable
fun PixelImage(bmp: ImageBitmap?, modifier: Modifier = Modifier, srcHeight: Int? = null, alpha: Float = 1f) {
    if (bmp == null) return
    Canvas(modifier) {
        drawPixel(bmp, Offset.Zero, size, srcHeight, alpha)
    }
}

fun DrawScope.drawPixel(bmp: ImageBitmap, topLeft: Offset, dst: Size, srcHeight: Int? = null, alpha: Float = 1f) {
    drawImage(
        bmp,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bmp.width, srcHeight ?: bmp.height),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(dst.width.toInt(), dst.height.toInt()),
        alpha = alpha,
        filterQuality = FilterQuality.None,
    )
}

@Composable
fun MonIcon(sprites: Sprites, species: Int, size: Int = 40) {
    // icon.png holds two 32x32 frames stacked vertically; draw the first.
    PixelImage(sprites.icon(species), Modifier.size(size.dp), srcHeight = 32)
}

@Composable
fun TypeBadge(data: GameData, type: Int) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(Bw.typeColor(type)).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(data.typeName(type), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** BW-style menu window: rounded dark-navy panel with a bright double border and a subtle inner sheen. */
@Composable
fun BwPanel(modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF3E4E60), Color(0xFF26323E))))
            .border(if (selected) 3.dp else 2.dp, if (selected) Bw.gold else Color(0xFF8FB0C8), shape)
            .padding(2.dp)
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp))
            .padding(8.dp),
        content = content,
    )
}

/**
 * BW-style capsule button: fully rounded, a lighter top and darker bottom for a glossy look, a bright rim,
 * and a dark "ledge" underneath so it reads as a raised key. All drawn in code.
 */
@Composable
fun BwButton(text: String, modifier: Modifier = Modifier, color: Color = Bw.accent, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val top = if (enabled) color.lighten(0.18f) else Color(0xFF586068)
    val bottom = if (enabled) color.darken(0.30f) else Color(0xFF3C444C)
    Box(modifier, contentAlignment = Alignment.Center) {
        // ledge / drop shadow
        Box(
            Modifier.matchParentSize().padding(top = 4.dp).clip(shape)
                .background(if (enabled) color.darken(0.55f) else Color(0xFF2A3038)),
        )
        Box(
            Modifier.matchParentSize().padding(bottom = 4.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .border(2.dp, Color.White.copy(alpha = if (enabled) 0.75f else 0.25f), shape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // top sheen
            Box(Modifier.matchParentSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).clip(RoundedCornerShape(percent = 50)).background(Color(0x26FFFFFF)))
            }
            Text(text, color = if (enabled) Color.White else Bw.textDim, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)

@Composable
fun HpBar(fraction: Float, modifier: Modifier = Modifier) {
    val c = when {
        fraction > 0.5f -> Bw.hpGreen
        fraction > 0.2f -> Bw.hpYellow
        else -> Bw.hpRed
    }
    Row(modifier.height(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("HP", color = Bw.gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 3.dp))
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color(0xFF384048))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
}

fun statusLabel(s: Status) = when (s) {
    Status.NONE -> null
    Status.SLEEP -> "잠듦" to Color(0xFF8C8C8C)
    Status.POISON -> "독" to Color(0xFFA040A0)
    Status.TOXIC -> "맹독" to Color(0xFF702070)
    Status.BURN -> "화상" to Color(0xFFE05030)
    Status.FREEZE -> "얼음" to Color(0xFF58B8E0)
    Status.PARALYSIS -> "마비" to Color(0xFFD8B020)
}

@Composable
fun StatusBadge(s: Status) {
    val l = statusLabel(s) ?: return
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(l.second).padding(horizontal = 4.dp)) {
        Text(l.first, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** Compact Pokémon summary used by the rental and swap screens. */
@Composable
fun MonCard(vm: GameViewModel, info: MonInfo, selectedIndex: Int?, modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    val data = vm.data
    BwPanel(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), selected = selectedIndex != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelImage(vm.sprites.front(info.species.id), Modifier.size(64.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.species.nameKo, color = Bw.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (selectedIndex != null) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.clip(RoundedCornerShape(50)).background(Bw.gold).padding(horizontal = 6.dp)) {
                            Text("${selectedIndex + 1}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { info.species.types.forEach { TypeBadge(data, it) } }
                Text("특성 ${data.abilityName(info.ability)}", color = Bw.textDim, fontSize = 11.sp)
                Text("도구 ${data.itemName(info.set.item)}", color = Bw.textDim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        val moves = info.set.moves.map { data.move(it) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            moves.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { m ->
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Bw.typeColor(m.type).copy(alpha = 0.85f))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) { Text(m.nameKo, color = Color.White, fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        val st = info.stats
        Text(
            "HP${st[Stat.HP]} 공${st[Stat.ATK]} 방${st[Stat.DEF]} 특공${st[Stat.SPA]} 특방${st[Stat.SPD]} 스${st[Stat.SPE]}",
            color = Bw.textDim, fontSize = 10.sp,
        )
        Text("${data.natures.getOrNull(info.set.nature)?.nameKo ?: ""} · IV ${info.mon.iv} · Lv.${info.mon.level}", color = Bw.textDim, fontSize = 10.sp)
    }
}
