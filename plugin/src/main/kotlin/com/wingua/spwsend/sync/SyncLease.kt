package com.wingua.spwsend.sync

/** 单个任务占用槽位；活动请求退出以前，不因释放或租约超时开放新任务。 */
class SyncLease(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEnded: (String, String) -> Unit = { _, _ -> },
) {
    private var owner: String? = null
    private var task: String? = null
    private var deadline = 0L
    private var active = 0
    private var releasing = false
    private var endingReason: String? = null

    @Synchronized
    fun busy(): Boolean {
        expire()
        return task != null
    }

    @Synchronized
    fun matches(who: String, id: String): Boolean {
        expire()
        return !releasing && owner == who && task == id
    }

    @Synchronized
    fun acquire(who: String, id: String): Boolean {
        expire()
        if (who.isEmpty() || id.isEmpty() || id.length > 128 || (task != null && !matches(who, id)))
            return false
        owner = who
        task = id
        deadline = now() + 45_000
        return true
    }

    @Synchronized
    fun renew(who: String, id: String): Boolean {
        if (!matches(who, id)) return false
        deadline = now() + 45_000
        return true
    }

    @Synchronized
    fun enter(who: String, id: String): Boolean {
        if (!renew(who, id)) return false
        active++
        return true
    }

    @Synchronized
    fun leave() {
        check(active > 0)
        active--
        if (!releasing) deadline = now() + 45_000
        expire()
    }

    @Synchronized
    fun release(who: String, id: String) {
        if (!matches(who, id)) return
        releasing = true
        expire()
    }

    @Synchronized
    fun cancel(who: String, id: String): Boolean {
        if (!matches(who, id)) return false
        releasing = true
        endingReason = "同步已取消"
        expire()
        return true
    }

    @Synchronized
    fun clear(reason: String = "同步连接已断开") {
        if (active > 0) {
            releasing = true
            endingReason = reason
            return
        }
        val previous = task
        owner = null
        task = null
        releasing = false
        deadline = 0
        endingReason = null
        if (previous != null) onEnded(previous, reason)
    }

    private fun expire() {
        if (task != null && active == 0 && (releasing || now() >= deadline)) {
            clear(endingReason ?: if (releasing) "同步未正常结束" else "连接超时，同步已中断")
        }
    }
}
