package com.wingua.spwsend.core

/** 协议 v2 常量（与 MySend 安卓端完全一致）。 */
object Constants {
    const val PROTO = 2
    const val DISCOVERY_PORT = 53317
    const val DEFAULT_HTTP_PORT = 53318

    /** 旧版独立模组的默认 HTTP 端口，仅用于升级时一次性迁移。 */
    const val LEGACY_HTTP_PORT = 54318

    /** 配置结构版本：低于该版本时执行旧值迁移。 */
    const val CONFIG_VERSION = 2

    const val ANNOUNCE_INTERVAL_MS = 2_000L
    const val PEER_TIMEOUT_MS = 12_000L
    const val CONTROL_TIMEOUT_MS = 10_000
    const val INDEX_TIMEOUT_MS = 60_000
    const val MAX_FILE_TIMEOUT_MS = 600_000
    const val APP_ID = "spw-send-mod"
    const val APP_VERSION = "0.6"

    /** 设备名称最大字符数（按 Unicode 码点计）。 */
    const val DEVICE_NAME_MAX = 32
    const val MIN_PEER = 1
    const val PART_DIR = ".mysend-part"
    const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /** MySend 协议头。 */
    const val HEADER_PROTO = "X-MySend-Proto"
    const val HEADER_TOKEN = "X-MySend-Token"
}
