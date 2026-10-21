@file:OptIn(UnstableSaltUiApi::class)

package com.wingua.spwsend.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.ButtonType
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Surface
import com.moriafly.salt.ui.Text
import com.moriafly.salt.ui.UnstableSaltUiApi
import com.wingua.spwsend.core.Mapping
import com.wingua.spwsend.core.PathUtil

/**
 * 宿主导航根组件之后追加的全屏面板。
 *
 * 使用 SaltUI 官方主题与组件，不创建任何 AWT 窗口。
 */
@Composable
fun SendOverlay() {
    if (!SendPanelState.visible) return
    val appearance = remember { HostAppearanceReader.read() }
    val systemDark = remember { readSystemDarkTheme() }
    val dark = appearance.dark ?: systemDark
    SaltTheme(configs = SaltConfigs.default(isDarkTheme = dark)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(SaltTheme.colors.background)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
        ) {
            SendPanel()
        }
    }
}

@Composable
private fun SendPanel() {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    SendController.requestClose()
                    true
                } else {
                    false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        if (maxWidth >= 860.dp) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Header()
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.width(320.dp).fillMaxHeight()) {
                        LocalInfoPanelCard()
                        Spacer(Modifier.height(12.dp))
                        SectionLabel("手机设备")
                        Spacer(Modifier.height(6.dp))
                        DeviceSection(Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(12.dp))
                        MappingSection(Modifier.fillMaxWidth().height(236.dp))
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        SyncSection()
                        Spacer(Modifier.height(12.dp))
                        ResultSection(Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(12.dp))
                        LogSection(Modifier.fillMaxWidth().height(142.dp))
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Header()
                Spacer(Modifier.height(12.dp))
                LocalInfoPanelCard()
                Spacer(Modifier.height(12.dp))
                SectionLabel("手机设备")
                Spacer(Modifier.height(6.dp))
                DeviceSection(Modifier.fillMaxWidth().height(240.dp))
                Spacer(Modifier.height(12.dp))
                MappingSection(Modifier.fillMaxWidth().height(230.dp))
                Spacer(Modifier.height(12.dp))
                SyncSection()
                Spacer(Modifier.height(12.dp))
                ResultSection(Modifier.fillMaxWidth().height(300.dp))
                Spacer(Modifier.height(12.dp))
                LogSection(Modifier.fillMaxWidth())
            }
        }
        DialogLayer()
    }
}

@Composable
private fun Header() {
    val colors = SaltTheme.colors
    val success = successColor()
    val warning = warningColor()
    val error = errorColor()
    val running = SendController.serviceRunning
    val statusColor = when {
        SendController.startupError != null -> error
        SendController.syncActive || SendController.compareActive -> warning
        running -> success
        else -> error
    }
    val statusText = when {
        SendController.startupError != null -> "服务未启动"
        SendController.syncActive -> "同步中 · 端口 ${SendController.port}"
        SendController.compareActive -> "对比中 · 端口 ${SendController.port}"
        running -> "服务在线 · 端口 ${SendController.port}"
        else -> "服务未启动"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("SPW-Send", style = SaltTheme.textStyles.largeTitle, color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "电脑 → 手机 · 只同步新增文件",
                style = SaltTheme.textStyles.sub,
                color = colors.subText
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(6.dp))
                Text(statusText, style = SaltTheme.textStyles.sub, color = statusColor)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { SendController.requestClose() },
                text = "关闭",
                type = ButtonType.Sub
            )
        }
    }
}

@Composable
private fun LocalInfoPanelCard() {
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        color = SaltTheme.colors.subBackground,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("本机", style = SaltTheme.textStyles.main, fontWeight = FontWeight.Bold)
            InfoRow("设备", SendController.deviceName.ifEmpty { "—" })
            InfoRow("端口", if (SendController.port > 0) SendController.port.toString() else "未监听")
            InfoRow("会话", SendController.session.take(8))
            Spacer(Modifier.height(2.dp))
            Text(
                "手机与电脑需在同一 Wi-Fi，",
                style = SaltTheme.textStyles.sub,
                color = SaltTheme.colors.subText
            )
            Text(
                "也可让电脑连接手机热点。",
                style = SaltTheme.textStyles.sub,
                color = SaltTheme.colors.subText
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(48.dp),
            style = SaltTheme.textStyles.sub,
            color = SaltTheme.colors.subText
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = SaltTheme.textStyles.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = SaltTheme.textStyles.sub,
        color = SaltTheme.colors.subText,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun DeviceSection(modifier: Modifier = Modifier) {
    val peers = SendController.peers
    PanelCard(
        modifier = modifier,
        color = SaltTheme.colors.subBackground,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        if (peers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                Text(
                    "正在搜索同一局域网内的手机…",
                    style = SaltTheme.textStyles.sub,
                    color = SaltTheme.colors.subText
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                peers.forEach { peer -> DeviceRow(peer) }
            }
        }
    }
}

@Composable
private fun DeviceRow(peer: PeerUi) {
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    peer.name,
                    style = SaltTheme.textStyles.main,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val sub = if (peer.connected) {
                    "已连接"
                } else {
                    if (peer.appVersion.isEmpty()) "可连接" else "可连接 · v${peer.appVersion}"
                }
                Text(
                    sub,
                    style = SaltTheme.textStyles.sub,
                    color = if (peer.connected) successColor() else SaltTheme.colors.subText
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { SendController.toggleConnection(peer.id) },
                text = if (peer.connected) "断开" else "连接",
                enabled = !SendController.actionLocked,
                type = if (peer.connected) {
                    ButtonType.Sub
                } else {
                    ButtonType.Highlight
                }
            )
        }
    }
}

@Composable
private fun MappingSection(modifier: Modifier = Modifier) {
    val mappings = SendController.mappings
    PanelCard(
        modifier = modifier,
        color = SaltTheme.colors.subBackground,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "同步映射",
                    modifier = Modifier.weight(1f),
                    style = SaltTheme.textStyles.main,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { SendController.beginAddMapping() },
                    text = "新增",
                    enabled = !SendController.syncActive && !SendController.compareActive,
                    type = ButtonType.Sub
                )
                Spacer(Modifier.width(6.dp))
                DangerButton(
                    onClick = { SendController.deleteCurrentMapping() },
                    text = "删除",
                    enabled = !SendController.syncActive && SendController.currentMappingName != null
                )
            }
            Spacer(Modifier.height(8.dp))
            if (mappings.isEmpty()) {
                Text(
                    "尚未配置映射，点击「新增」选择电脑上的源目录。",
                    style = SaltTheme.textStyles.sub,
                    color = SaltTheme.colors.subText
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mappings.forEach { mapping -> MappingRow(mapping) }
                }
            }
        }
    }
}

@Composable
private fun MappingRow(mapping: Mapping) {
    val selected = mapping.id == SendController.selectedMappingId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) SaltTheme.colors.highlight.copy(alpha = 0.12f) else Color.Transparent
            )
            .clickable { SendController.selectMapping(mapping.id) }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            shortText(mapping.name, 24),
            style = SaltTheme.textStyles.main,
            color = if (selected) SaltTheme.colors.highlight else SaltTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            shortPath(mapping.sourcePath, 46),
            style = SaltTheme.textStyles.sub,
            color = SaltTheme.colors.subText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SyncSection() {
    val mappings = SendController.mappings
    val current = mappings.firstOrNull { it.id == SendController.selectedMappingId }
        ?: mappings.firstOrNull()
    val canCompare = mappings.isNotEmpty() &&
        SendController.hasConnectedPeer &&
        !SendController.compareActive &&
        !SendController.syncActive
    val canSync = SendController.pending?.files?.isNotEmpty() == true &&
        !SendController.compareActive &&
        !SendController.syncActive

    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        color = SaltTheme.colors.subBackground,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("同步任务", style = SaltTheme.textStyles.main, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mappings.isEmpty()) {
                    Text(
                        "尚未配置映射",
                        style = SaltTheme.textStyles.sub,
                        color = SaltTheme.colors.subText
                    )
                } else {
                    mappings.forEach { mapping ->
                        val selected = mapping.id == SendController.selectedMappingId
                        Button(
                            onClick = { SendController.selectMapping(mapping.id) },
                            text = shortText(mapping.name, 14),
                            enabled = !SendController.syncActive && !SendController.compareActive,
                            type = if (selected) {
                                ButtonType.Highlight
                            } else {
                                ButtonType.Sub
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "源目录：${current?.let { shortPath(it.sourcePath, 56) } ?: "未选择"}",
                style = SaltTheme.textStyles.sub,
                color = SaltTheme.colors.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { SendController.compareNow() },
                    text = "对比",
                    enabled = canCompare,
                    type = ButtonType.Sub
                )
                Button(
                    onClick = { SendController.syncNow() },
                    text = "同步",
                    enabled = canSync,
                    type = ButtonType.Highlight
                )
                Button(
                    onClick = { SendController.cancelSync() },
                    text = "取消",
                    enabled = SendController.syncActive,
                    type = ButtonType.Sub
                )
            }
            Spacer(Modifier.height(10.dp))
            ProgressBar(SendController.progress)
            Spacer(Modifier.height(6.dp))
            Text(
                SendController.status,
                style = SaltTheme.textStyles.sub,
                color = SaltTheme.colors.subText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SaltTheme.colors.stroke.copy(alpha = 0.35f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(6.dp))
                .background(SaltTheme.colors.highlight)
        )
    }
}

@Composable
private fun ResultSection(modifier: Modifier = Modifier) {
    PanelCard(
        modifier = modifier,
        color = SaltTheme.colors.subBackground,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("对比结果", style = SaltTheme.textStyles.main, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val pending = SendController.pending
            when {
                pending == null -> Text(
                    SendController.resultText,
                    style = SaltTheme.textStyles.sub,
                    color = SaltTheme.colors.subText
                )

                pending.files.isEmpty() -> Column {
                    Text(
                        "映射：${pending.mapping.name}",
                        style = SaltTheme.textStyles.sub,
                        color = SaltTheme.colors.subText
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("手机端已存在全部文件，无需同步。", style = SaltTheme.textStyles.sub)
                }

                else -> {
                    Text(
                        "映射：${pending.mapping.name}   新增 ${pending.files.size} 个 · " +
                            PathUtil.formatBytes(pending.totalBytes),
                        style = SaltTheme.textStyles.sub,
                        color = SaltTheme.colors.subText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(pending.files.take(200)) { file ->
                            Text(
                                "+ ${file.relativePath}",
                                style = SaltTheme.textStyles.sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (pending.files.size > 200) {
                            item {
                                Text(
                                    "… 其余 ${pending.files.size - 200} 个文件",
                                    style = SaltTheme.textStyles.sub,
                                    color = SaltTheme.colors.subText
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogSection(modifier: Modifier = Modifier) {
    PanelCard(
        modifier = modifier,
        color = SaltTheme.colors.subBackground,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("最近同步", style = SaltTheme.textStyles.main, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (SendController.logs.isEmpty()) {
                Text(
                    "暂无同步记录",
                    style = SaltTheme.textStyles.sub,
                    color = SaltTheme.colors.subText
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SendController.logs.forEach { line ->
                        Text(
                            line,
                            style = SaltTheme.textStyles.sub,
                            color = SaltTheme.colors.subText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.DialogLayer() {
    val dialog = SendController.dialog
    val nameInput = SendController.nameInput
    var shown by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(dialog, nameInput) {
        if (dialog != null) shown = dialog
        if (nameInput != null) shown = nameInput
    }
    AnimatedVisibility(
        visible = dialog != null || nameInput != null,
        enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 460.dp)
            ) {
                when (val content = shown) {
                    is NameInput -> NameInputPanelCard(content)
                    is PanelDialog -> MessagePanelCard(content)
                }
            }
        }
    }
}

@Composable
private fun MessagePanelCard(dialog: PanelDialog) {
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        color = SaltTheme.colors.popup,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                dialog.title,
                style = SaltTheme.textStyles.main,
                fontWeight = FontWeight.Bold,
                color = if (dialog.success) successColor() else errorColor()
            )
            Spacer(Modifier.height(8.dp))
            Text(dialog.message, style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText)
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dialog.showCancel) {
                    Button(
                        onClick = { SendController.cancelDialog() },
                        text = "取消",
                        type = ButtonType.Sub
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (dialog.showCancel) {
                    DangerButton(
                        onClick = { SendController.confirmDialog() },
                        text = dialog.confirmText
                    )
                } else {
                    Button(
                        onClick = { SendController.confirmDialog() },
                        text = dialog.confirmText,
                        type = ButtonType.Highlight
                    )
                }
            }
        }
    }
}

@Composable
private fun NameInputPanelCard(input: NameInput) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(input.path) {
        runCatching { focusRequester.requestFocus() }
    }
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        color = SaltTheme.colors.popup,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("新增映射", style = SaltTheme.textStyles.main, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(input.label, style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SaltTheme.colors.background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { focusRequester.requestFocus() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = input.value,
                    onValueChange = { SendController.updateNameInput(it) },
                    singleLine = true,
                    textStyle = SaltTheme.textStyles.main.copy(color = SaltTheme.colors.text),
                    cursorBrush = SolidColor(SaltTheme.colors.highlight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                SendController.submitNameInput()
                                true
                            } else {
                                false
                            }
                        }
                )
            }
            input.error?.let { error ->
                Spacer(Modifier.height(6.dp))
                Text(error, style = SaltTheme.textStyles.sub, color = errorColor())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                shortPath(input.path, 56),
                style = SaltTheme.textStyles.sub,
                color = SaltTheme.colors.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { SendController.cancelNameInput() },
                    text = "取消",
                    type = ButtonType.Sub
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { SendController.submitNameInput() },
                    text = "确定",
                    type = ButtonType.Highlight
                )
            }
        }
    }
}

/** SaltUI 2.x 没有 Card，这里用基础布局组合出同等的卡片容器。 */
@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val fill = if (color == Color.Unspecified) SaltTheme.colors.subBackground else color
    val stroke = border ?: BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(stroke, shape),
        content = content
    )
}

@Composable
private fun DangerButton(
    onClick: () -> Unit,
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val danger = errorColor()
    BasicButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = danger.copy(alpha = 0.16f)
    ) {
        Text(text = text, color = if (enabled) danger else SaltTheme.colors.subText)
    }
}

@Composable
private fun successColor(): Color =
    if (SaltTheme.configs.isDarkTheme) Color(0xFF7BE0A0) else Color(0xFF2E9E5B)

@Composable
private fun warningColor(): Color =
    if (SaltTheme.configs.isDarkTheme) Color(0xFFFFC46B) else Color(0xFFB26A00)

@Composable
private fun errorColor(): Color =
    if (SaltTheme.configs.isDarkTheme) Color(0xFFFF9B9B) else Color(0xFFCF4545)

private fun readSystemDarkTheme(): Boolean = runCatching {
    val process = Runtime.getRuntime().exec(
        arrayOf(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme"
        )
    )
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    when {
        output.contains("0x0") -> true
        output.contains("0x1") -> false
        else -> true
    }
}.getOrDefault(true)

private fun shortText(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max - 1) + "…"

private fun shortPath(path: String, max: Int): String =
    if (path.length <= max) path else "…" + path.takeLast(max - 1)
