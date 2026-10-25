package com.wingua.spwsend.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wingua.spwsend.core.ConfigStore
import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.LocalFile
import com.wingua.spwsend.core.LogStore
import com.wingua.spwsend.core.Mapping
import com.wingua.spwsend.core.PathUtil
import com.wingua.spwsend.core.SyncLog
import com.wingua.spwsend.net.Peer
import com.wingua.spwsend.sync.CompareResult
import com.wingua.spwsend.sync.SyncCore
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

    var resultText by mutableStateOf("选择映射并点击「对比」，这里会列出手机端尚不存在的新增文件。")
        private set

    var logs by mutableStateOf<List<String>>(emptyList())
        private set

    var compareActive by mutableStateOf(false)
        private set

    var syncActive by mutableStateOf(false)
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
        get() = compareActive || syncActive || actionPeerId != null

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
        refreshFromCore()
        refreshLogs()
        cancelRequested = false
        status = core.startupError ?: if (core.isRunning) "服务已就绪" else "服务未启动"
    }

    @Synchronized
    fun shutdown() {
        listenerAttached = false
        cancelRequested = true
        core.onChanged = null
        runCatching { core.stop() }
        executor.shutdownNow()
        serviceRunning = false
        port = 0
        compareActive = false
        syncActive = false
        actionPeerId = null
        pending = null
        peers = emptyList()
        dialog = null
        nameInput = null
        status = "已关闭"
    }

    /** 关闭面板：同步或对比进行中时不允许关闭。 */
    fun requestClose() {
        if (syncActive || compareActive || core.busy) {
            dialog = PanelDialog(
                title = "请稍候",
                message = "正在对比或同步，请等待完成后再关闭面板。",
                success = false
            )
            return
        }
        stopCore()
        SendPanelState.close()
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
        dialog = null
        nameInput = null
        status = "已关闭"
    }

    fun selectMapping(id: String) {
        if (id == selectedMappingId) return
        if (core.busy || syncActive) return
        selectedMappingId = id
        pending = null
        progress = 0f
        resultText = "映射已切换，请重新对比。"
        status = "已选择映射"
    }

    fun toggleConnection(peerId: String) {
        if (actionLocked || core.busy) return
        val peer = core.peers().firstOrNull { it.id == peerId } ?: return
        actionPeerId = peerId
        val disconnecting = core.isConnected(peer.id)
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
                val ok = runCatching { core.connect(peer) }.getOrDefault(false)
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
        if (compareActive || syncActive || core.busy) return
        val mapping = currentMapping()
        if (mapping == null) {
            dialog = PanelDialog("尚未配置映射", "请先在左侧新增映射，并选择电脑上的源目录。", false)
            return
        }
        if (!File(mapping.sourcePath).isDirectory) {
            dialog = PanelDialog("源目录不存在", "映射「${mapping.name}」的源目录已不可用，请检查路径。", false)
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
        val mappingId = mapping.id
        executor.execute {
            try {
                val result = core.compare(peer, mapping)
                post {
                    compareActive = false
                    if (selectedMappingId != mappingId) return@post
                    pending = result
                    resultText = formatCompare(result)
                    progress = if (result.files.isEmpty()) 1f else 0f
                    status = if (result.files.isEmpty()) {
                        "没有新增文件"
                    } else {
                        "新增 ${result.files.size} 个文件，共 ${PathUtil.formatBytes(result.totalBytes)}"
                    }
                }
            } catch (e: Exception) {
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
        if (compareActive || syncActive || core.busy) return
        val current = pending
        if (current == null || current.files.isEmpty()) {
            dialog = PanelDialog("无需同步", "请先点击「对比」，确认是否存在新增文件。", false)
            return
        }
        val peer = connectedPeer()
        if (peer == null) {
            dialog = PanelDialog("尚未连接", "请先连接手机后再同步。", false)
            return
        }

        cancelRequested = false
        syncActive = true
        progress = 0f
        status = "正在同步 0/${current.files.size}"
        executor.execute {
            var outcome: SyncOutcome? = null
            var failure: Exception? = null
            try {
                outcome = core.sync(
                    peer = peer,
                    pending = current,
                    onProgress = { processed, total, path ->
                        post {
                            progress = if (total == 0) 0f else processed.toFloat() / total
                            status = "已处理 $processed/$total · ${shortPath(path, 42)}"
                        }
                    },
                    cancelled = { cancelRequested }
                )
            } catch (e: Exception) {
                failure = e
            }
            post {
                syncActive = false
                pending = null
                progress = 1f
                resultText = "同步已完成，点击「对比」可重新检查新增文件。"
                status = "同步结束"
                refreshLogs()
                refreshFromCore()
                val result = outcome
                val error = failure
                dialog = when {
                    error != null -> PanelDialog("同步失败", error.message ?: "未知错误", false)
                    result == null -> PanelDialog("同步失败", "未获取同步结果。", false)
                    result.cancelled -> PanelDialog(
                        "已取消同步",
                        "已完成 ${result.log.success}/${result.log.total} 个文件。",
                        false
                    )

                    result.log.failed > 0 -> PanelDialog(
                        "同步完成（部分失败）",
                        failureText(result.log),
                        false
                    )

                    else -> PanelDialog(
                        "同步完成",
                        "共 ${result.log.success} 个文件，${PathUtil.formatBytes(result.log.bytes)}。",
                        true
                    )
                }
            }
        }
    }

    fun cancelSync() {
        if (!syncActive) return
        cancelRequested = true
        status = "正在取消…"
    }

    fun beginAddMapping() {
        if (core.busy || syncActive) {
            dialog = PanelDialog("请稍候", "正在同步，暂时不能修改映射。", false)
            return
        }
        val dir = pickDirectory() ?: return
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
        if (core.busy || syncActive || compareActive) {
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
            executor.execute {
                runCatching { core.updatePort(port) }
                post {
                    refreshFromCore()
                    val actual = core.actualPort
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
                dialogTitle = "选择电脑上的源目录"
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

    private fun formatCompare(result: CompareResult): String {
        val builder = StringBuilder()
        builder.append("映射：").append(result.mapping.name).append('\n')
        builder.append("新增文件：").append(result.files.size).append(" 个 · ")
            .append(PathUtil.formatBytes(result.totalBytes)).append("\n\n")
        if (result.files.isEmpty()) {
            builder.append("手机端已存在全部文件，无需同步。")
        } else {
            val preview = result.files.take(50)
            for (file: LocalFile in preview) {
                builder.append("+ ").append(file.relativePath).append('\n')
            }
            if (result.files.size > preview.size) {
                builder.append("… 其余 ").append(result.files.size - preview.size).append(" 个文件")
            }
        }
        return builder.toString()
    }

    private fun failureText(log: SyncLog): String {
        val builder = StringBuilder()
        builder.append("成功 ${log.success}/${log.total} 个文件，")
            .append("失败 ${log.failed} 个。\n\n")
        for (failure in log.failures.take(6)) {
            builder.append(failure.path).append('\n')
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
