@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.wingua.spwsend.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moriafly.salt.ui.Button as SaltButton
import com.moriafly.salt.ui.ButtonAppearance
import com.moriafly.salt.ui.ButtonIntent
import com.moriafly.salt.ui.Card as SaltCard
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.SaltColors
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Text as SaltText
import com.moriafly.salt.ui.screen.BasicScreen
import com.wingua.spwsend.core.Mapping
import com.wingua.spwsend.core.PathUtil

/**
 * 宿主导航根组件之后追加的全屏页面。
 *
 * 页面沿用宿主的标题栏样式（56dp 返回按钮行）与字体；
 * 背景按窗口样式取近似实色，不接管宿主导航、不参与材质竞争。
 * 不创建任何 AWT 窗口。
 */
@Composable
fun SendOverlay() {
    if (!SendPanelState.visible) return
    val appearance = remember { HostAppearanceReader.read() }
    val systemDark = remember { readSystemDarkTheme() }
    val dark = appearance.dark ?: systemDark
    val style = AppearanceSettings.backgroundStyle
    val glass = BackgroundStyle.usesGlass(style, appearance.windowStyle)
    val wallpaper = remember(glass) {
        if (glass) Wallpaper.load() else null
    }
    val palette = remember(dark, glass) {
        hostPalette(dark, glass)
    }
    val hostFont = remember(appearance.fontStrategy, appearance.customFontFilePath) {
        HostFont.load(appearance.fontStrategy, appearance.customFontFilePath)
    }
    val baseStyles = SaltTheme.textStyles
    val textStyles = baseStyles.copy(
        main = baseStyles.main.copy(fontFamily = hostFont),
        sub = baseStyles.sub.copy(fontFamily = hostFont),
        paragraph = baseStyles.paragraph.copy(fontFamily = hostFont),
        largeTitle = baseStyles.largeTitle.copy(fontFamily = hostFont)
    )
    SaltTheme(
        configs = SaltConfigs.default(dark),
        dynamicColors = SaltDynamicColors(light = palette, dark = palette),
        textStyles = textStyles
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
        ) {
            BackgroundLayer(
                glass = glass,
                dark = dark,
                wallpaper = wallpaper,
                solidColor = palette.background
            )
            SendPanel()
            DialogLayer()
        }
    }
}

/**
 * 页面背景：优先壁纸磨砂（跟随宿主或磨砂玻璃），其次渐变，最后实色。
 */
@Composable
private fun BackgroundLayer(
    glass: Boolean,
    dark: Boolean,
    wallpaper: ImageBitmap?,
    solidColor: Color
) {
    Box(Modifier.fillMaxSize()) {
        when {
            wallpaper != null -> {
                Image(
                    bitmap = wallpaper,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(radius = 64.dp)
                )
                Box(Modifier.fillMaxSize().background(glassTint(dark)))
            }

            glass -> GradientBackground(dark)

            else -> Box(Modifier.fillMaxSize().background(solidColor))
        }
    }
}

/** 壁纸上的磨砂色调层，兼顾通透与文字对比度。 */
private fun glassTint(dark: Boolean): Color = if (dark) {
    Color(0xFF161C28).copy(alpha = 0.55f)
} else {
    Color(0xFFF4F4F6).copy(alpha = 0.68f)
}

@Composable
private fun GradientBackground(dark: Boolean) {
    val colors = if (dark) {
        listOf(Color(0xFF2B2B31), Color(0xFF1E1E22))
    } else {
        listOf(Color(0xFFF9F9FC), Color(0xFFEDEDF3))
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors)))
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
        // 在 BoxWithConstraints 作用域内先取值，避免进入 BasicScreen 的 BoxScope 后无法访问。
        val wide = maxWidth >= 860.dp
        BasicScreen(
            onBack = { SendController.requestClose() },
            modifier = Modifier.fillMaxSize(),
            title = "SPW-Send",
            subtitle = "电脑 → 手机 · 只同步新增文件",
            toolButtons = { StatusIndicator() }
        ) { padding ->
            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        // 下沿留空，避开宿主底部的迷你播放条。
                        .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 96.dp)
                ) {
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
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp)
                ) {
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
        }
    }
}

/** 标题栏右侧的连接状态，沿用宿主的次文本样式。 */
@Composable
private fun StatusIndicator() {
    val statusColor = when {
        SendController.startupError != null -> errorColor()
        SendController.syncActive || SendController.compareActive -> warningColor()
        SendController.serviceRunning -> successColor()
        else -> errorColor()
    }
    val statusText = when {
        SendController.startupError != null -> "服务未启动"
        SendController.syncActive -> "同步中 · ${SendController.port}"
        SendController.compareActive -> "对比中 · ${SendController.port}"
        SendController.serviceRunning -> "服务在线 · ${SendController.port}"
        else -> "服务未启动"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
        Spacer(Modifier.width(6.dp))
        PanelText(statusText, style = PanelSub, color = statusColor)
    }
}

@Composable
private fun LocalInfoPanelCard() {
    val appearance = remember { HostAppearanceReader.read() }
    val glass = BackgroundStyle.usesGlass(
        AppearanceSettings.backgroundStyle,
        appearance.windowStyle
    )
    val materialLabel = buildString {
        append(appearance.windowStyle ?: "未知")
        append(if (glass) " · 磨砂玻璃" else " · 经典纯色")
    }
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PanelText("本机", style = PanelMain, fontWeight = FontWeight.Bold)
            InfoRow("设备", SendController.deviceName.ifEmpty { "—" })
            InfoRow("端口", if (SendController.port > 0) SendController.port.toString() else "未监听")
            InfoRow("会话", SendController.session.take(8))
            InfoRow("材质", materialLabel)
            Spacer(Modifier.height(2.dp))
            PanelText(
                "手机与电脑需在同一 Wi-Fi，",
                style = PanelSub,
                color = panelColors.subText
            )
            PanelText(
                "也可让电脑连接手机热点。",
                style = PanelSub,
                color = panelColors.subText
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PanelText(
            label,
            modifier = Modifier.width(48.dp),
            style = PanelSub,
            color = panelColors.subText
        )
        PanelText(
            value,
            modifier = Modifier.weight(1f),
            style = PanelSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    PanelText(
        text,
        style = PanelSub,
        color = panelColors.subText,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun DeviceSection(modifier: Modifier = Modifier) {
    val peers = SendController.peers
    PanelCard(modifier = modifier) {
        if (peers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                PanelText(
                    "正在搜索同一局域网内的手机…",
                    style = PanelSub,
                    color = panelColors.subText
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
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                PanelText(
                    peer.name,
                    style = PanelMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val sub = if (peer.connected) {
                    "已连接"
                } else {
                    if (peer.appVersion.isEmpty()) "可连接" else "可连接 · v${peer.appVersion}"
                }
                PanelText(
                    sub,
                    style = PanelSub,
                    color = if (peer.connected) successColor() else panelColors.subText
                )
            }
            Spacer(Modifier.width(8.dp))
            PanelButton(
                onClick = { SendController.toggleConnection(peer.id) },
                text = if (peer.connected) "断开" else "连接",
                enabled = !SendController.actionLocked,
                type = if (peer.connected) PanelButtonType.Sub else PanelButtonType.Highlight
            )
        }
    }
}

@Composable
private fun MappingSection(modifier: Modifier = Modifier) {
    val mappings = SendController.mappings
    PanelCard(modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PanelText(
                    "同步映射",
                    modifier = Modifier.weight(1f),
                    style = PanelMain,
                    fontWeight = FontWeight.Bold
                )
                PanelButton(
                    onClick = { SendController.beginAddMapping() },
                    text = "新增",
                    enabled = !SendController.syncActive && !SendController.compareActive,
                    type = PanelButtonType.Sub
                )
                Spacer(Modifier.width(6.dp))
                PanelButton(
                    onClick = { SendController.deleteCurrentMapping() },
                    text = "删除",
                    enabled = !SendController.syncActive && SendController.currentMappingName != null,
                    type = PanelButtonType.Danger
                )
            }
            Spacer(Modifier.height(8.dp))
            if (mappings.isEmpty()) {
                PanelText(
                    "尚未配置映射，点击「新增」选择电脑上的源目录。",
                    style = PanelSub,
                    color = panelColors.subText
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
                if (selected) panelColors.highlight.copy(alpha = 0.12f) else Color.Transparent
            )
            .clickable { SendController.selectMapping(mapping.id) }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        PanelText(
            shortText(mapping.name, 24),
            style = PanelMain,
            color = if (selected) panelColors.highlight else panelColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        PanelText(
            shortPath(mapping.sourcePath, 46),
            style = PanelSub,
            color = panelColors.subText,
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

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            PanelText("同步任务", style = PanelMain, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mappings.isEmpty()) {
                    PanelText(
                        "尚未配置映射",
                        style = PanelSub,
                        color = panelColors.subText
                    )
                } else {
                    mappings.forEach { mapping ->
                        val selected = mapping.id == SendController.selectedMappingId
                        PanelButton(
                            onClick = { SendController.selectMapping(mapping.id) },
                            text = shortText(mapping.name, 14),
                            enabled = !SendController.syncActive && !SendController.compareActive,
                            type = if (selected) {
                                PanelButtonType.Highlight
                            } else {
                                PanelButtonType.Sub
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PanelText(
                "源目录：${current?.let { shortPath(it.sourcePath, 56) } ?: "未选择"}",
                style = PanelSub,
                color = panelColors.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelButton(
                    onClick = { SendController.compareNow() },
                    text = "对比",
                    enabled = canCompare,
                    type = PanelButtonType.Sub
                )
                PanelButton(
                    onClick = { SendController.syncNow() },
                    text = "同步",
                    enabled = canSync,
                    type = PanelButtonType.Highlight
                )
                PanelButton(
                    onClick = { SendController.cancelSync() },
                    text = "取消",
                    enabled = SendController.syncActive,
                    type = PanelButtonType.Sub
                )
            }
            Spacer(Modifier.height(10.dp))
            ProgressBar(SendController.progress)
            Spacer(Modifier.height(6.dp))
            PanelText(
                SendController.status,
                style = PanelSub,
                color = panelColors.subText,
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
            .background(panelColors.stroke.copy(alpha = 0.35f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(6.dp))
                .background(panelColors.highlight)
        )
    }
}

@Composable
private fun ResultSection(modifier: Modifier = Modifier) {
    PanelCard(modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            PanelText("对比结果", style = PanelMain, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val pending = SendController.pending
            when {
                pending == null -> PanelText(
                    SendController.resultText,
                    style = PanelSub,
                    color = panelColors.subText
                )

                pending.files.isEmpty() -> Column {
                    PanelText(
                        "映射：${pending.mapping.name}",
                        style = PanelSub,
                        color = panelColors.subText
                    )
                    Spacer(Modifier.height(4.dp))
                    PanelText("手机端已存在全部文件，无需同步。", style = PanelSub)
                }

                else -> {
                    PanelText(
                        "映射：${pending.mapping.name}   新增 ${pending.files.size} 个 · " +
                            PathUtil.formatBytes(pending.totalBytes),
                        style = PanelSub,
                        color = panelColors.subText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(pending.files.take(200)) { file ->
                            PanelText(
                                "+ ${file.relativePath}",
                                style = PanelSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (pending.files.size > 200) {
                            item {
                                PanelText(
                                    "… 其余 ${pending.files.size - 200} 个文件",
                                    style = PanelSub,
                                    color = panelColors.subText
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
    PanelCard(modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            PanelText("最近同步", style = PanelMain, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (SendController.logs.isEmpty()) {
                PanelText(
                    "暂无同步记录",
                    style = PanelSub,
                    color = panelColors.subText
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SendController.logs.forEach { line ->
                        PanelText(
                            line,
                            style = PanelSub,
                            color = panelColors.subText,
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
        color = panelColors.popup
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            PanelText(
                dialog.title,
                style = PanelMain,
                fontWeight = FontWeight.Bold,
                color = if (dialog.success) successColor() else errorColor()
            )
            Spacer(Modifier.height(8.dp))
            PanelText(dialog.message, style = PanelSub, color = panelColors.subText)
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dialog.showCancel) {
                    PanelButton(
                        onClick = { SendController.cancelDialog() },
                        text = "取消",
                        type = PanelButtonType.Sub
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (dialog.showCancel) {
                    PanelButton(
                        onClick = { SendController.confirmDialog() },
                        text = dialog.confirmText,
                        type = PanelButtonType.Danger
                    )
                } else {
                    PanelButton(
                        onClick = { SendController.confirmDialog() },
                        text = dialog.confirmText,
                        type = PanelButtonType.Highlight
                    )
                }
            }
        }
    }
}

@Composable
private fun NameInputPanelCard(input: NameInput) {
    val focusRequester = remember { FocusRequester() }
    // 保留输入法组合状态，避免中文候选提交后拼音残留在两侧。
    var fieldValue by remember(input.path) { mutableStateOf(TextFieldValue(input.value)) }
    LaunchedEffect(input.path) {
        runCatching { focusRequester.requestFocus() }
    }
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        color = panelColors.popup
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            PanelText("新增映射", style = PanelMain, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            PanelText(input.label, style = PanelSub, color = panelColors.subText)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SaltTheme.colors.subBackground)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { focusRequester.requestFocus() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { value ->
                        fieldValue = value
                        SendController.updateNameInput(value.text)
                    },
                    singleLine = true,
                    textStyle = PanelMain.copy(
                        color = SaltTheme.colors.text,
                        fontFamily = SaltTheme.textStyles.main.fontFamily
                    ),
                    cursorBrush = SolidColor(panelColors.highlight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            val composing = fieldValue.composition != null
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                !composing
                            ) {
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
                PanelText(error, style = PanelSub, color = errorColor())
            }
            Spacer(Modifier.height(6.dp))
            PanelText(
                shortPath(input.path, 56),
                style = PanelSub,
                color = panelColors.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelButton(
                    onClick = { SendController.cancelNameInput() },
                    text = "取消",
                    type = PanelButtonType.Sub
                )
                Spacer(Modifier.width(8.dp))
                PanelButton(
                    onClick = { SendController.submitNameInput() },
                    text = "确定",
                    type = PanelButtonType.Highlight
                )
            }
        }
    }
}

@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    SaltCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (color == Color.Unspecified) SaltTheme.colors.subBackground else color,
        border = BorderStroke(Dp.Hairline, SaltTheme.colors.stroke)
    ) {
        content()
    }
}

private enum class PanelButtonType { Highlight, Sub, Danger }

@Composable
private fun PanelButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    type: PanelButtonType = PanelButtonType.Highlight
) {
    val appearance = when (type) {
        PanelButtonType.Highlight -> ButtonAppearance.Filled
        PanelButtonType.Sub -> ButtonAppearance.Subtle
        PanelButtonType.Danger -> ButtonAppearance.Filled
    }
    SaltButton(
        onClick = onClick,
        text = text,
        modifier = modifier,
        enabled = enabled,
        appearance = appearance,
        intent = if (type == PanelButtonType.Danger) {
            ButtonIntent.Destructive
        } else {
            ButtonIntent.Normal
        },
        size = ControlSize.Small
    )
}

@Composable
private fun PanelText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = PanelMain,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    fontWeight: FontWeight? = null
) {
    SaltText(
        text = text,
        modifier = modifier,
        color = if (color == Color.Unspecified) SaltTheme.colors.text else color,
        style = style.copy(
            fontWeight = fontWeight ?: style.fontWeight,
            fontFamily = SaltTheme.textStyles.main.fontFamily
        ),
        maxLines = maxLines,
        overflow = overflow
    )
}

private val PanelMain = TextStyle(
    fontSize = 14.sp,
    lineHeight = 20.sp
)

private val PanelSub = TextStyle(
    fontSize = 12.sp,
    lineHeight = 17.sp
)

private val panelColors: SaltColors
    @Composable
    @ReadOnlyComposable
    get() = SaltTheme.colors

@Composable
private fun successColor(): Color = SaltTheme.colors.success

@Composable
private fun warningColor(): Color = SaltTheme.colors.warning

@Composable
private fun errorColor(): Color = SaltTheme.colors.error

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
