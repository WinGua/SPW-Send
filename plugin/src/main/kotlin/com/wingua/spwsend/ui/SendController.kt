package com.wingua.spwsend.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wingua.spwsend.core.ConfigStore
import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.Diagnostics
import com.wingua.spwsend.core.LogStore
import com.wingua.spwsend.core.Mapping
import com.wingua.spwsend.core.PathUtil
import com.wingua.spwsend.core.SyncLog
import com.wingua.spwsend.net.Peer
import com.wingua.spwsend.sync.CompareResult
import com.wingua.spwsend.sync.SyncCore
import com.wingua.spwsend.sync.SyncDirection
import com.wingua.spwsend.sync.SyncOutcome
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** 设备列表的只读快照，避免 Compose 直接观察可变 Peer。 */
data class PeerUi(
    val id: String,
    val name: String,
    val connected: Boolean,
    val appVersion: String
)

/** 页面内弹窗。 */
data class PanelDialog(
    val title: String,
    val message: String,
    val success: Boolean,
    val confirmText: String = "关闭",
    val showCancel: Boolean = false,
    val onConfirm: (() -> Unit)? = null
)

/** 名称输入的用途。 */
enum class NameInputMode { Mapping, Device, Port }

/** 名称输入弹窗。 */
data class NameInput(
    val title: String,
    val label: String,
    val path: String,
    val value: String,
    val mode: NameInputMode = NameInputMode.Mapping,
    val error: String? = null
)

/**
 * 把 [SyncCore] 的业务状态桥接为 Compose 状态。
 *
 * 网络操作在单线程 executor 执行，所有可观察状态只在 EDT 写入。
 */
object SendController {

    private val logStore = LogStore()
    private val core = SyncCore(ConfigStore(), logStore)

    init {
        Diagnostics.stateProvider = { stateSnapshot() }
        Diagnostics.configProvider = { configSnapshot() }
    }

    private var executor: ExecutorService = newExecutor()

    @Volatile
    private var cancelRequested = false

    private var listenerAttached = false

    var serviceRunning by mutableStateOf(false)
        private set

    var port by mutableStateOf(0)
        private set

    var startupError by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf("就绪")
        private set

    var progress by mutableStateOf(0f)
        private set

    var peers by mutableStateOf<List<PeerUi>>(emptyList())
        private set

    var mappings by mutableStateOf<List<Mapping>>(emptyList())
        private set

    var selectedMappingId by mutableStateOf<String?>(null)
        private set

    var selectedPeerId by mutableStateOf<String?>(null)
        private set

    var pending by mutableStateOf<CompareResult?>(null)
        private set

    var resultText by mutableStateOf("选择映射并点击「对比」，这里会列出将发送与将接收的新增文件。")
        private set

    var logs by mutableStateOf<List<String>>(emptyList())
        private set

    var compareActive by mutableStateOf(false)
        private set

    var syncActive by mutableStateOf(false)
        private set

    /** 手机正在向本机推送文件。 */
    var inboundActive by mutableStateOf(false)
        private set

    var actionPeerId by mutableStateOf<String?>(null)
        private set

    var dialog by mutableStateOf<PanelDialog?>(null)
        private set

    var nameInput by mutableStateOf<NameInput?>(null)
        private set

    var deviceName by mutableStateOf("")
        private set

    val session: String
        get() = core.session

    val hasConnectedPeer: Boolean
        get() = peers.any { it.connected }

    val actionLocked: Boolean
        get() = compareActive || syncActive || inboundActive || actionPeerId != null

    val currentMappingName: String?
        get() = currentMapping()?.name

    @Synchronized
    fun start() {
        if (executor.isShutdown) executor = newExecutor()
        if (!listenerAttached) {
            listenerAttached = true
            core.onChanged = { post { refreshFromCore() } }
        }
        runCatching { core.start() }
            .onFailure { Diagnostics.record("service", "启动异常: ${it.message}") }
        refreshFromCore()
        refreshLogs()
        cancelRequested = false
        status = core.startupError ?: if (core.isRunning) "服务已就绪" else "服务未启动"
        Diagnostics.record(
            "service",
            "启动 运行=${core.isRunning} 端口=${core.actualPort} 错误=${core.startupError ?: "无"}"
        )
    }

    @Synchronized
    fun shutdown() {
        Diagnostics.record("service", "关闭")
        listenerAttached = false
        cancelRequested = true
        core.onChanged = null
        runCatching { core.stop() }
            .onFailure { Diagnostics.record("service", "关闭异常: ${it.message}") }
        executor.shutdownNow()
        serviceRunning = false
        port = 0
        compareActive = false
        syncActive = false
        inboundActive = false
        actionPeerId = null
        pending = null
        peers = emptyList()
        dialog = null
        nameInput = null
        status = "已关闭"
    }

    /** 关闭面板：同步、对比或手机推送进行中时不允许关闭。 */
    fun requestClose() {
        Diagnostics.record("panel", "请求关闭")
        if (syncActive || compareActive || inboundActive || core.busy) {
            dialog = PanelDialog(
                title = "请稍候",
                message = "正在对比、同步或接收文件，请等待完成后再关闭面板。",
                success = false
            )
            return
        }
        stopCore()
        SendPanelState.close()
    }

    /**
     * 宿主左侧导航栏被点击。
     *
     * 有二级弹窗时先收起弹窗；任务进行中由 [requestClose] 给出提示，不打断同步。
     */
    fun onSidebarClick() {
        when {
            nameInput != null -> nameInput = null
            dialog != null -> dialog = null
            else -> requestClose()
        }
    }

    private fun stopCore() {
        runCatching { core.stop() }
        serviceRunning = false
        port = 0
        actionPeerId = null
        pending = null
        progress = 0f
        peers = emptyList()
        selectedPeerId = null
        inboundActive = false
        dialog = null
        nameInput = null
        status = "已关闭"
    }

    fun selectMapping(id: String) {
        if (id == selectedMappingId) return
        if (core.busy || syncActive || inboundActive) return
        selectedMappingId = id
        pending = null
        progress = 0f
        resultText = "映射已切换，请重新对比。"
        status = "已选择映射"
        val mappingName = mappings.firstOrNull { it.id == id }?.name ?: id
        Diagnostics.record("mapping", "选择 $mappingName")
    }

    fun toggleConnection(peerId: String) {
        if (actionLocked || core.busy) return
        val peer = core.peers().firstOrNull { it.id == peerId } ?: return
        actionPeerId = peerId
        val disconnecting = core.isConnected(peer.id)
        Diagnostics.record("connect", if (disconnecting) "断开 ${peer.name}" else "连接 ${peer.name}")
        status = if (disconnecting) "正在断开…" else "正在连接…"
        executor.execute {
            if (disconnecting) {
                runCatching { core.disconnect(peer) }
                post {
                    actionPeerId = null
                    if (selectedPeerId == peer.id) selectedPeerId = null
                    status = "已断开"
                    refreshFromCore()
                }
            } else {
                val ok = runCatching { core.connect(peer) }
                    .onFailure { Diagnostics.record("connect", "连接 ${peer.name} 失败: ${it.message}") }
                    .getOrDefault(false)
                post {
                    actionPeerId = null
                    if (ok) {
                        selectedPeerId = peer.id
                        status = "已连接 ${peer.name}"
                    } else {
                        status = "连接失败"
                        dialog = PanelDialog(
                            title = "连接失败",
                            message = "无法连接「${peer.name}」。请确认手机端已打开 MySend，且与电脑处于同一局域网。",
                            success = false
                        )
                    }
                    refreshFromCore()
                }
            }
        }
    }

    fun compareNow() {
        if (compareActive || syncActive || inboundActive || core.busy) return
        val mapping = currentMapping()
        if (mapping == null) {
            dialog = PanelDialog("尚未配置映射", "请先在左侧新增映射，并选择电脑上的同步目录。", false)
            return
        }
        if (!File(mapping.sourcePath).isDirectory) {
            dialog = PanelDialog("同步目录不存在", "映射「${mapping.name}」的同步目录已不可用，请检查路径。", false)
            return
        }
        val peer = connectedPeer()
        if (peer == null) {
            dialog = PanelDialog("尚未连接", "请先在上方设备列表连接手机。", false)
            return
        }

        pending = null
        progress = 0f
        compareActive = true
        status = "正在对比…"
        Diagnostics.record("compare", "开始 映射=${mapping.name}")
        val mappingId = mapping.id
        executor.execute {
            try {
                val result = core.compare(peer, mapping)
                post {
                    compareActive = false
                    if (selectedMappingId != mappingId) return@post
                    pending = result
                    resultText = formatCompare(result)
                    progress = if (result.isEmpty) 1f else 0f
                    Diagnostics.record(
                        "compare",
                        "完成 映射=${result.mapping.name} 发送=${result.toSend.size} 接收=${result.toReceive.size}"
                    )
                    status = when {
                        result.isEmpty -> "两端文件一致"
                        !result.receiveSupported && result.toReceive.isNotEmpty() ->
                            "将发送 ${result.toSend.size} 个；对端版本较旧，无法接收 ${result.toReceive.size} 个文件"
                        else -> "将发送 ${result.toSend.size} 个 · " +
                            "${PathUtil.formatBytes(result.sendBytes)}；将接收 " +
                            "${result.toReceive.size} 个 · ${PathUtil.formatBytes(result.receiveBytes)}"
                    }
                }
            } catch (e: Exception) {
                Diagnostics.record("compare", "失败 映射=${mapping.name}: ${e.message}")
                post {
                    compareActive = false
                    if (selectedMappingId != mappingId) return@post
                    pending = null
                    resultText = "对比失败：${e.message}"
                    status = "对比失败"
                    dialog = PanelDialog("对比失败", e.message ?: "未知错误", false)
                }
            }
        }
    }

    fun syncNow() {
        if (compareActive || syncActive || inboundActive || core.busy) return
        val current = pending
        if (current == null || current.isEmpty) {
            dialog = PanelDialog("无需同步", "请先点击「对比」，确认是否存在新增文件。", false)
            return
        }
        if (current.toSend.isEmpty() && !current.receiveSupported &&
            current.toReceive.isNotEmpty()
        ) {
            dialog = PanelDialog(
                "对端版本较旧",
                "当前只能从手机接收文件，但对方的 MySend 版本较旧，无法接收。"
                    + "请前往 MySend 发布页升级到最新版。",
                false
            )
            return
        }
        val peer = connectedPeer()
        if (peer == null) {
            dialog = PanelDialog("尚未连接", "请先连接手机后再同步。", false)
            return
        }

        val total = current.toSend.size +
            (if (current.receiveSupported) current.toReceive.size else 0)
        cancelRequested = false
        syncActive = true
        progress = 0f
        status = "正在同步 0/$total"
        Diagnostics.record(
            "sync",
            "开始 映射=${current.mapping.name} 发送=${current.toSend.size} 接收=${current.toReceive.size}"
        )
        executor.execute {
            var outcome: SyncOutcome? = null
            var failure: Exception? = null
            try {
                outcome = core.sync(
                    peer = peer,
                    pending = current,
                    onProgress = { processed, totalCount, path, direction ->
                        post {
                            progress = if (totalCount == 0) 0f else processed.toFloat() / totalCount
                            val label = if (direction == SyncDirection.Send) "发送" else "接收"
                            status = "$label $processed/$totalCount · ${shortPath(path, 40)}"
                        }
                    },
                    cancelled = { cancelRequested }
                )
            } catch (e: Exception) {
                failure = e
                Diagnostics.record("sync", "异常 映射=${current.mapping.name}: ${e.message}")
            }
            post {
                syncActive = false
                pending = null
                progress = 1f
                refreshLogs()
                refreshFromCore()
                resultText = "同步已完成，点击「对比」可重新检查新增文件。"
                status = "同步结束"
                val result = outcome
                val error = failure
                if (result != null) {
                    Diagnostics.record(
                        "sync",
                        "结束 发送=${result.log.sentCount} 接收=${result.log.receivedCount} " +
                            "失败=${result.log.failed} 取消=${result.cancelled}"
                    )
                } else if (error != null) {
                    Diagnostics.record("sync", "失败: ${error.message}")
                }
                dialog = when {
                    error != null -> PanelDialog("同步失败", error.message ?: "未知错误", false)
                    result == null -> PanelDialog("同步失败", "未获取同步结果。", false)
                    result.cancelled -> PanelDialog(
                        "已取消同步",
                        syncSummary(result.log),
                        false
                    )

                    result.log.failed > 0 -> PanelDialog(
                        "同步完成（部分失败）",
                        failureText(result.log),
                        false
                    )

                    result.receiveSkipped -> PanelDialog(
                        "同步完成",
                        syncSummary(result.log) +
                            "\n\n对端 MySend 版本较旧，已跳过接收 " +
                            "${(current.toReceive.size - result.log.receivedCount).coerceAtLeast(0)}" +
                            " 个文件。请前往 MySend 发布页升级到最新版。",
                        true
                    )

                    else -> PanelDialog(
                        "同步完成",
                        syncSummary(result.log),
                        true
                    )
                }
            }
        }
    }

    fun cancelSync() {
        if (!syncActive) return
        Diagnostics.record("sync", "请求取消")
        cancelRequested = true
        status = "正在取消…"
    }

    fun beginAddMapping() {
        if (core.busy || syncActive || inboundActive) {
            dialog = PanelDialog("请稍候", "正在同步，暂时不能修改映射。", false)
            return
        }
        val dir = pickDirectory() ?: return
        Diagnostics.record("mapping", "新增 目录=$dir")
        val fallback = File(dir).name.ifEmpty { "音乐" }
        nameInput = NameInput(
            title = "新增映射",
            label = "映射名称（需与手机端一致）",
            path = dir,
            value = fallback
        )
    }

    /** 编辑本机设备名称，手机端会以该名称显示电脑。 */
    fun beginEditDeviceName() {
        nameInput = NameInput(
            title = "修改设备名称",
            label = "设备名称",
            path = "",
            value = deviceName,
            mode = NameInputMode.Device
        )
    }

    /** 编辑 HTTP 服务端口，保存后重启局域网服务。 */
    fun beginEditPort() {
        if (actionLocked || core.busy) {
            dialog = PanelDialog("请稍候", "正在连接、对比或同步，暂时不能修改端口。", false)
            return
        }
        nameInput = NameInput(
            title = "修改服务端口",
            label = "端口号（1024–65535，保存后重启服务）",
            path = "",
            value = core.config.port.toString(),
            mode = NameInputMode.Port
        )
    }

    fun deleteCurrentMapping() {
        if (core.busy || syncActive || compareActive || inboundActive) {
            dialog = PanelDialog("请稍候", "正在对比或同步，暂时不能修改映射。", false)
            return
        }
        val mapping = currentMapping()
        if (mapping == null) {
            dialog = PanelDialog("尚未配置映射", "当前没有可删除的映射。", false)
            return
        }
        val targetId = mapping.id
        dialog = PanelDialog(
            title = "删除映射",
            message = "确定删除「${mapping.name}」吗？电脑和手机上的文件都不会被删除。",
            success = false,
            confirmText = "删除",
            showCancel = true,
            onConfirm = {
                Diagnostics.record("mapping", "删除 ${mapping.name}")
                core.updateMappings(mappings.filterNot { it.id == targetId })
                selectedMappingId = null
                pending = null
                progress = 0f
                resultText = "映射已删除，请选择其他映射。"
                status = "已删除映射"
                refreshFromCore()
            }
        )
    }

    fun updateNameInput(value: String) {
        nameInput = nameInput?.copy(value = value, error = null)
    }

    fun submitNameInput() {
        val input = nameInput ?: return
        val name = input.value.trim()
        if (name.isEmpty()) {
            nameInput = input.copy(
                error = when (input.mode) {
                    NameInputMode.Device -> "请填写设备名称"
                    NameInputMode.Port -> "请填写端口号"
                    NameInputMode.Mapping -> "请填写映射名称"
                }
            )
            return
        }
        if (input.mode == NameInputMode.Port) {
            val port = name.toIntOrNull()
            if (port == null || port < 1024 || port > 65535) {
                nameInput = input.copy(error = "请输入 1024–65535 之间的端口号")
                return
            }
            if (port == core.config.port) {
                nameInput = null
                status = "端口未变化"
                return
            }
            nameInput = null
            status = "正在切换端口…"
            Diagnostics.record("service", "切换端口 $port")
            executor.execute {
                runCatching { core.updatePort(port) }
                    .onFailure { Diagnostics.record("service", "切换端口失败: ${it.message}") }
                post {
                    refreshFromCore()
                    val actual = core.actualPort
                    Diagnostics.record("service", "端口结果 期望=$port 实际=$actual")
                    when {
                        actual == port -> status = "服务端口已切换为 $port"
                        actual > 0 -> {
                            status = "端口 $port 被占用，已改用 $actual"
                            dialog = PanelDialog(
                                "端口已占用",
                                "端口 $port 无法监听，已改用系统分配的端口 $actual。",
                                false
                            )
                        }

                        else -> {
                            status = "端口切换失败"
                            dialog = PanelDialog(
                                "端口切换失败",
                                core.startupError ?: "服务未能重新启动。",
                                false
                            )
                        }
                    }
                }
            }
            return
        }
        if (input.mode == NameInputMode.Device) {
            if (name.codePointCount(0, name.length) > Constants.DEVICE_NAME_MAX) {
                nameInput = input.copy(error = "设备名称最多 ${Constants.DEVICE_NAME_MAX} 个字符")
                return
            }
            core.updateDeviceName(name)
            Diagnostics.record("device", "修改名称 $name")
            nameInput = null
            status = "设备名称已更新为「$name」"
            refreshFromCore()
            return
        }
        if (mappings.any { it.name == name }) {
            nameInput = input.copy(error = "已存在同名映射「$name」")
            return
        }
        val added = Mapping(
            id = UUID.randomUUID().toString(),
            name = name,
            sourcePath = input.path
        )
        core.updateMappings(mappings + added)
        Diagnostics.record("mapping", "添加 ${added.name} -> ${added.sourcePath}")
        selectedMappingId = added.id
        pending = null
        resultText = "映射已新增，请重新对比。"
        status = "已添加映射「$name」"
        nameInput = null
        refreshFromCore()
    }

    fun cancelNameInput() {
        nameInput = null
    }

    fun clearLogs() {
        if (logs.isEmpty()) return
        dialog = PanelDialog(
            title = "清除同步日志",
            message = "确定清除本机全部同步日志吗？已同步的文件不会受影响。",
            success = false,
            confirmText = "清除",
            showCancel = true,
            onConfirm = {
                logStore.clear()
                Diagnostics.record("log", "清除同步日志")
                refreshLogs()
                status = "已清除同步日志"
            }
        )
    }

    fun cancelDialog() {
        dialog = null
    }

    fun confirmDialog() {
        val current = dialog ?: return
        dialog = null
        current.onConfirm?.invoke()
    }

    // ---------------- 内部实现 ----------------

    private fun refreshFromCore() {
        val config = core.config
        mappings = config.mappings
        deviceName = config.deviceName
        if (selectedMappingId == null || config.mappings.none { it.id == selectedMappingId }) {
            selectedMappingId = config.mappings.firstOrNull()?.id
        }

        val snapshot = core.peers().map { peer ->
            PeerUi(
                id = peer.id,
                name = peer.name,
                connected = core.isConnected(peer.id),
                appVersion = peer.appVersion
            )
        }
        peers = snapshot
        serviceRunning = core.isRunning
        port = core.actualPort
        startupError = core.startupError

        val inbound = core.isInboundActive
        val wasInbound = inboundActive
        inboundActive = inbound
        if (inbound && !syncActive && !compareActive) {
            status = "手机正在推送文件…"
        } else if (wasInbound && !inbound && !syncActive && !compareActive) {
            status = "接收结束"
        }

        val connected = snapshot.filter { it.connected }
        if (selectedPeerId == null || connected.none { it.id == selectedPeerId }) {
            selectedPeerId = connected.firstOrNull()?.id
        }
    }

    private fun refreshLogs() {
        logs = logStore.recent(5).map { it.summary() }
    }

    private fun connectedPeer(): Peer? {
        val snapshot = core.peers()
        val chosen = selectedPeerId
        if (chosen != null) {
            val target = snapshot.firstOrNull {
                it.id == chosen && core.isConnected(it.id)
            }
            if (target != null) return target
        }
        return snapshot.firstOrNull { core.isConnected(it.id) }
    }

    private fun currentMapping(): Mapping? {
        val all = mappings
        return all.firstOrNull { it.id == selectedMappingId } ?: all.firstOrNull()
    }

    private fun pickDirectory(): String? {
        var selected: String? = null
        val action = Runnable {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "选择电脑上的同步目录"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selected = chooser.selectedFile?.absolutePath
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run()
        } else {
            SwingUtilities.invokeAndWait(action)
        }
        return selected
    }

    private fun post(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater { block() }
        }
    }

    private fun newExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "spw-send-task").apply { isDaemon = true }
        }

    /** 崩溃报告使用的状态快照，不包含文件路径。 */
    private fun stateSnapshot(): String {
        val config = core.config
        val peerList = core.peers()
        val connected = peerList.count { core.isConnected(it.id) }
        val task = when {
            syncActive -> "同步中"
            compareActive -> "对比中"
            inboundActive -> "接收中"
            core.busy -> "忙碌"
            else -> "空闲"
        }
        return buildString {
            appendLine("服务: ${if (core.isRunning) "运行中" else "未运行"}")
            appendLine("端口: ${core.actualPort}")
            appendLine("任务: $task")
            appendLine("设备: ${peerList.size} 个，已连接 $connected")
            appendLine("映射: ${config.mappings.size} 个，当前 ${currentMapping()?.name ?: "无"}")
            appendLine("选择设备: ${selectedPeerId ?: "无"}")
            appendLine("进度: ${(progress * 100).toInt()}%")
            appendLine("状态文本: $status")
        }
    }

    /** 崩溃报告使用的配置摘要，包含映射路径以便定位。 */
    private fun configSnapshot(): String {
        val config = core.config
        return buildString {
            appendLine("设备名: ${config.deviceName}")
            appendLine("设备 ID: ${config.deviceId}")
            appendLine("配置端口: ${config.port}")
            if (config.mappings.isEmpty()) {
                appendLine("映射: 无")
            } else {
                config.mappings.forEach { appendLine("映射: ${it.name} -> ${it.sourcePath}") }
            }
        }
    }

    private fun formatCompare(result: CompareResult): String {
        val builder = StringBuilder()
        builder.append("映射：").append(result.mapping.name).append('\n')
        builder.append("将发送：").append(result.toSend.size).append(" 个 · ")
            .append(PathUtil.formatBytes(result.sendBytes)).append('\n')
        builder.append("将接收：").append(result.toReceive.size).append(" 个 · ")
            .append(PathUtil.formatBytes(result.receiveBytes)).append('\n')
        if (!result.receiveSupported && result.toReceive.isNotEmpty()) {
            builder.append("注意：对端 MySend 版本较旧，无法接收文件，")
                .append("请前往 MySend 发布页升级到最新版。\n")
        }
        builder.append('\n')
        if (result.isEmpty) {
            builder.append("两端文件一致，无需同步。")
            return builder.toString()
        }
        if (result.toSend.isNotEmpty()) {
            builder.append("↑ 待发送\n")
            val preview = result.toSend.take(50)
            for (file in preview) {
                builder.append("  ").append(file.relativePath).append('\n')
            }
            if (result.toSend.size > preview.size) {
                builder.append("  … 其余 ").append(result.toSend.size - preview.size)
                    .append(" 个\n")
            }
        }
        if (result.toReceive.isNotEmpty()) {
            builder.append("↓ 待接收\n")
            val preview = result.toReceive.take(50)
            for (file in preview) {
                builder.append("  ").append(file.relativePath).append('\n')
            }
            if (result.toReceive.size > preview.size) {
                builder.append("  … 其余 ").append(result.toReceive.size - preview.size)
                    .append(" 个\n")
            }
        }
        return builder.toString().trimEnd()
    }

    private fun syncSummary(log: SyncLog): String =
        "已发送 ${log.sentCount} 个（${PathUtil.formatBytes(log.sentBytes)}），" +
            "已接收 ${log.receivedCount} 个（${PathUtil.formatBytes(log.receivedBytes)}）。"

    private fun failureText(log: SyncLog): String {
        val builder = StringBuilder()
        builder.append("发送 ${log.sentCount} 个，接收 ${log.receivedCount} 个，")
            .append("失败 ${log.failed} 个。\n\n")
        for (failure in log.failures.take(6)) {
            val direction = when (failure.direction) {
                "send" -> "发送 "
                "recv" -> "接收 "
                else -> ""
            }
            builder.append(direction).append(failure.path).append('\n')
            builder.append("  原因：").append(failure.reason).append('\n')
        }
        if (log.failures.size > 6) {
            builder.append("… 其余 ").append(log.failures.size - 6).append(" 个失败文件")
        }
        return builder.toString()
    }

    private fun shortPath(path: String, max: Int): String =
        if (path.length <= max) path else "…" + path.takeLast(max - 1)
}
