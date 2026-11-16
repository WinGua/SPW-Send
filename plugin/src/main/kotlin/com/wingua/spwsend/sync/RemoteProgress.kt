package com.wingua.spwsend.sync

data class RemoteSyncProgress(
    val task: String,
    val mapping: String,
    val peer: String,
    val total: Int,
    val sent: Int = 0,
    val received: Int = 0,
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val bytes: Long = 0,
    val current: String = "",
    val sending: Boolean = false,
    val preparing: Boolean = true,
    val finished: Boolean = false,
    val failed: Int = 0,
    val error: String? = null,
    val started: Long = System.nanoTime(),
    val inFlight: Double = 0.0,
    val transferStarted: Long? = null,
    val finishedAt: Long? = null,
) {
    val done
        get() = sent + received

    val fraction: Float
        get() = if (total == 0) 0f else ((done + inFlight) / total).coerceIn(0.0, 1.0).toFloat()

    val speed
        get() =
            transferStarted?.let {
                bytes * 1e9 / ((finishedAt ?: System.nanoTime()) - it).coerceAtLeast(1)
            } ?: 0.0
}

class RemoteProgress(private val changed: () -> Unit) {
    @Volatile
    var snapshot: RemoteSyncProgress? = null
        private set

    private var bytes = 0L
    private var lastUpdate = 0L

    private data class FileBytes(val size: Long, var position: Long)

    private val files = HashMap<String, FileBytes>()

    private fun inFlight() =
        files.values.sumOf {
            if (it.size <= 0) 0.0 else (it.position.toDouble() / it.size).coerceIn(0.0, 1.0)
        }

    @Synchronized
    fun active(task: String) = snapshot?.let { it.task == task && !it.finished } == true

    @Synchronized
    fun start(task: String, name: String, peer: String, total: Int) {
        if (snapshot?.task == task) return
        bytes = 0
        files.clear()
        lastUpdate = 0
        snapshot = RemoteSyncProgress(task, name, peer, total)
        changed()
    }

    @Synchronized
    fun plan(task: String, total: Int) {
        if (!active(task)) return
        snapshot = snapshot!!.copy(total = total, preparing = false)
        changed()
    }

    @Synchronized
    fun file(task: String, name: String, sending: Boolean, size: Long = 0, offset: Long = 0) {
        if (!active(task)) return
        files[name] = FileBytes(size, offset)
        snapshot =
            snapshot!!.copy(
                current = name,
                sending = sending,
                preparing = false,
                inFlight = inFlight(),
                transferStarted = snapshot!!.transferStarted ?: System.nanoTime(),
            )
        changed()
    }

    @Synchronized
    fun bytes(task: String, count: Int, name: String = "") {
        if (!active(task)) return
        bytes += count
        files[name.ifEmpty { snapshot!!.current }]?.let { it.position += count }
        val now = System.nanoTime()
        if (now - lastUpdate >= 100_000_000) {
            lastUpdate = now
            snapshot = snapshot!!.copy(bytes = bytes, inFlight = inFlight())
            changed()
        }
    }

    @Synchronized
    fun complete(task: String, size: Long, sending: Boolean, name: String = "") {
        if (!active(task)) return
        files.remove(name.ifEmpty { snapshot!!.current })
        val s = snapshot!!
        snapshot =
            s.copy(
                bytes = bytes,
                inFlight = inFlight(),
                sent = s.sent + if (sending) 1 else 0,
                received = s.received + if (sending) 0 else 1,
                sentBytes = s.sentBytes + if (sending) size else 0,
                receivedBytes = s.receivedBytes + if (sending) 0 else size,
            )
        changed()
    }

    @Synchronized
    fun finish(
        task: String,
        sent: Int? = null,
        received: Int? = null,
        failed: Int = 0,
        error: String? = null,
    ) {
        if (!active(task)) return
        snapshot =
            snapshot!!.copy(
                sent = sent ?: snapshot!!.sent,
                received = received ?: snapshot!!.received,
                failed = failed,
                error = error,
                bytes = bytes,
                preparing = false,
                finished = true,
                finishedAt = System.nanoTime(),
            )
        changed()
    }

    fun interrupt(task: String, reason: String) = finish(task, error = reason)
}
