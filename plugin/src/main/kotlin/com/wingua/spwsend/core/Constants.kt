package com.wingua.spwsend.core

/** 协议 v2 常量。 */
object Constants {
    const val PROTO = 2
    const val DISCOVERY_PORT = 54317
    const val DEFAULT_HTTP_PORT = 54318
    const val ANNOUNCE_INTERVAL_MS = 2_000L
    const val PEER_TIMEOUT_MS = 12_000L
    const val CONTROL_TIMEOUT_MS = 10_000
    const val MAX_FILE_TIMEOUT_MS = 600_000
    const val APP_ID = "spw-send-mod"
    const val APP_VERSION = "0.1.0"
    const val MIN_PEER = 1
    const val PART_DIR = ".spw-send-part"
    const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
}
