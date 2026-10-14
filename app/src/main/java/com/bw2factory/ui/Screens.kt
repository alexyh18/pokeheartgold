package com.bw2factory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bw2factory.engine.MonInfo
import com.bw2factory.engine.Josa
import com.bw2factory.factory.FactoryRules
import com.bw2factory.factory.OpponentHint
import com.bw2factory.factory.Phase

@Composable
fun FactoryApp(vm: GameViewModel) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF14202C), Color(0xFF263646))))
            .systemBarsPadding(),
    ) {
        val session = vm.session
        if (session != null) {
            BattleScreen(vm, session)
        } else {
            var confirmForfeit by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize()) {
                Header(vm) { confirmForfeit = true }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (vm.state.phase) {
                        Phase.RENTAL -> RentalScreen(vm)
                        Phase.PRE_BATTLE -> PreBattleScreen(vm)
                        Phase.SWAP -> SwapScreen(vm)
                        Phase.ROUND_CLEAR -> RoundClearScreen(vm)
                        Phase.LOST -> LostScreen(vm)
                    }
                }
            }
            if (confirmForfeit) {
                val s = vm.state
                ConfirmDialog(
                    title = "연승을 포기할까요?",
                    body = "현재 ${s.streak}연승을 포기하고 처음(1번째 열차)부터 다시 시작합니다.\n최고 기록은 그대로 남습니다.",
                    confirm = "포기하고 처음부터",
                    onConfirm = { confirmForfeit = false; vm.forfeit() },
                    onCancel = { confirmForfeit = false },
                )
            }
        }
    }
}

@Composable
private fun Header(vm: GameViewModel, onForfeit: () -> Unit) {
    val s = vm.state
    // Forfeit is available whenever a run is in progress (not already lost, and past the very first choice).
    val canForfeit = s.phase != Phase.LOST && (s.streak > 0 || s.phase != Phase.RENTAL)
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF101820)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("배틀서브웨이 · 렌탈토너먼트(마스터)", color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                if (s.phase == Phase.LOST) s.phase.title
                else "라운드 ${s.round + 1} · ${s.battleInRound + 1}번째 배틀 · ${s.phase.title}",
                color = Bw.textDim, fontSize = 12.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("연승 ${s.streak}", color = Bw.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("최고 ${s.bestStreak} · 교환 ${s.trades}", color = Bw.textDim, fontSize = 11.sp)
        }
        if (canForfeit) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF503038))
                    .border(1.dp, Bw.accent, RoundedCornerShape(8.dp))
                    .clickable(onClick = onForfeit).padding(horizontal = 8.dp, vertical = 6.dp),
            ) { Text("포기", color = Bw.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        BwPanel(Modifier.fillMaxWidth(0.85f)) {
            Text(title, color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Bw.text, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BwButton("취소", Modifier.weight(1f), color = Color(0xFF4878B8), onClick = onCancel)
                BwButton(confirm, Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun RentalScreen(vm: GameViewModel) {
    val s = vm.state
    val picks = remember(s.rentals) { mutableStateListOf<Int>() }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        HintPanel(vm)
        Text(
            "렌탈 포켓몬 6마리 중 3마리를 고르세요. 처음 고른 포켓몬이 선두로 나갑니다.",
            color = Bw.text, fontSize = 13.sp, modifier = Modifier.padding(4.dp),
        )
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(s.rentals) { i, mon ->
                val info = remember(mon) { MonInfo(vm.data, mon) }
                val sel = picks.indexOf(i).takeIf { it >= 0 }
                MonCard(vm, info, sel) {
                    if (i in picks) picks.remove(i) else if (picks.size < 3) picks.add(i)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        BwButton(
            if (picks.size == 3) "이 3마리로 도전한다" else "${picks.size}/3 선택",
            Modifier.fillMaxWidth(),
            enabled = picks.size == 3,
        ) { vm.chooseRentals(picks.toList()) }
    }
}

@Composable
private fun PartyOrder(vm: GameViewModel) {
    val s = vm.state
    var first by remember(s.party) { mutableStateOf<Int?>(null) }
    Text("파티 (탭 두 번으로 순서 변경 · 왼쪽이 선두)", color = Bw.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.party.forEachIndexed { i, mon ->
            val info = remember(mon) { MonInfo(vm.data, mon) }
            BwPanel(
                Modifier.weight(1f).clickable {
                    val f = first
                    if (f == null) first = i
                    else {
                        if (f != i) {
                            val order = s.party.indices.toMutableList()
                            order[f] = i; order[i] = f
                            vm.reorder(order)
                        }
                        first = null
                    }
                },
                selected = first == i,
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PixelImage(vm.sprites.front(info.species.id), Modifier.size(64.dp))
                    Text(info.species.nameKo, color = Bw.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(vm.data.itemName(info.set.item), color = Bw.textDim, fontSize = 10.sp, maxLines = 1)
                    if (i == 0) Text("선두", color = Bw.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PreBattleScreen(vm: GameViewModel) {
    val s = vm.state
    val t = vm.trainer()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        vm.battleResult?.let {
            Text("직전 배틀: $it", color = Bw.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }
        BwPanel(Modifier.fillMaxWidth(), selected = vm.isBoss) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(vm.sprites.trainer(t.sprite), Modifier.size(width = 86.dp * 1.2f, height = 128.dp * 1.2f))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${s.battleNumber}번째 상대", color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(t.classKo, color = Bw.textDim, fontSize = 13.sp)
                    Text(t.name, color = Bw.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Spacer(Modifier.height(6.dp))
                    if (vm.isBoss) {
                        Text(
                            "배틀서브웨이 보스가 기다리고 있다! 이기면 7연전 클리어!",
                            color = Bw.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text("라운드 ${s.round + 1} · ${s.battleInRound + 1} / 7", color = Bw.textDim, fontSize = 12.sp)
                        if (s.battleInRound == 6) Text("이 라운드의 마지막 상대다!", color = Bw.accent, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HintPanel(vm, showTrainer = false)
        Spacer(Modifier.height(12.dp))
        PartyOrder(vm)
        Spacer(Modifier.height(16.dp))
        BwButton("승부한다!", Modifier.fillMaxWidth()) { vm.startBattle() }
    }
}

@Composable
private fun SwapScreen(vm: GameViewModel) {
    val s = vm.state
    var mine by remember(s.party, s.swapOffer) { mutableStateOf<Int?>(null) }
    var theirs by remember(s.party, s.swapOffer) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            "승리했다! 방금 싸운 상대의 포켓몬 1마리와 교환할 수 있습니다.\n내보낼 포켓몬과 받을 포켓몬을 하나씩 고르세요.",
            color = Bw.text, fontSize = 13.sp, modifier = Modifier.padding(4.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HintPanel(vm)
            Text("내 파티 (내보낼 포켓몬)", color = Bw.gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            s.party.forEachIndexed { i, mon ->
                val info = remember(mon) { MonInfo(vm.data, mon) }
                MonCard(vm, info, if (mine == i) 0 else null, Modifier.fillMaxWidth()) { mine = if (mine == i) null else i }
            }
            Text("상대 파티 (받을 포켓몬)", color = Bw.gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            s.swapOffer.forEachIndexed { i, mon ->
                val info = remember(mon) { MonInfo(vm.data, mon) }
                MonCard(vm, info, if (theirs == i) 0 else null, Modifier.fillMaxWidth()) { theirs = if (theirs == i) null else i }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BwButton("교환하지 않는다", Modifier.weight(1f), color = Color(0xFF4878B8)) { vm.swap(null, null) }
            BwButton("교환한다", Modifier.weight(1f), enabled = mine != null && theirs != null) { vm.swap(mine, theirs) }
        }
    }
}

@Composable
private fun RoundClearScreen(vm: GameViewModel) {
    val s = vm.state
    CenterMessage(
        title = "라운드 ${s.round} 클리어!",
        body = "7연전을 모두 이겼습니다!\n현재 ${s.streak}연승 · 기록이 저장되었습니다.\n" +
            (if (s.streak == 49) "배틀서브웨이 보스를 쓰러뜨렸다!\n" else "") +
            "다음 라운드에서는 새로운 렌탈 포켓몬을 고릅니다.",
        button = "다음 라운드로",
    ) { vm.nextRound() }
}

@Composable
private fun LostScreen(vm: GameViewModel) {
    val s = vm.state
    CenterMessage(
        title = "도전 종료",
        body = "${s.lastResult}연승으로 도전이 끝났습니다.\n최고 기록: ${s.bestStreak}연승\n클리어한 라운드 합계: ${s.roundsCleared}",
        button = "처음부터 다시 도전",
    ) { vm.newChallenge() }
}

@Composable
private fun CenterMessage(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BwPanel(Modifier.fillMaxWidth()) {
            Text(title, color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, color = Bw.text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        BwButton(button, Modifier.fillMaxWidth(), onClick = onClick)
    }
}

/** The next opponent and what is known about their team (more is hidden in later rounds). */
@Composable
fun HintPanel(vm: GameViewModel, showTrainer: Boolean = true) {
    val s = vm.state
    val hint = vm.hint ?: return
    val t = vm.trainer()
    BwPanel(Modifier.fillMaxWidth(), selected = vm.isBoss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showTrainer) {
                PixelImage(vm.sprites.trainer(t.sprite), Modifier.size(width = 43.dp, height = 64.dp))
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (showTrainer) "다음 상대 · ${s.battleNumber}번째 · ${t.classKo} ${t.name}" else "상대 정보",
                    color = Bw.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                when (hint) {
                    is OpponentHint.Species -> {
                        Text(
                            if (hint.species.size == 3) "상대가 데리고 있는 포켓몬" else "상대 포켓몬 중 2마리",
                            color = Bw.textDim, fontSize = 11.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            hint.species.forEach { sp ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    PixelImage(vm.sprites.front(sp), Modifier.size(52.dp))
                                    Text(vm.data.species.getValue(sp).nameKo, color = Bw.text, fontSize = 11.sp)
                                }
                            }
                            if (hint.species.size < 3) Text("+ ?", color = Bw.textDim, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    is OpponentHint.LeadAndMove -> {
                        Text("선두 포켓몬과 그 기술 하나", color = Bw.textDim, fontSize = 11.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelImage(vm.sprites.front(hint.species), Modifier.size(52.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(vm.data.species.getValue(hint.species).nameKo, color = Bw.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(4.dp)).background(Bw.typeColor(hint.move.type))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) { Text(hint.move.nameKo, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(4.dp))
                                    Text(Josa.eulReul(hint.move.nameKo).removePrefix(hint.move.nameKo) + " 쓴다", color = Bw.textDim, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    is OpponentHint.MainType -> {
                        Text("상대가 주로 사용하는 타입", color = Bw.textDim, fontSize = 11.sp)
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(Bw.typeColor(hint.type))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) { Text(vm.data.typeName(hint.type), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}
