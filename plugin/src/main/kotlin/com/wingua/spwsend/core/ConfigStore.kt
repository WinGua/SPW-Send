package com.wingua.spwsend.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 一组「名称 → 电脑同步目录」映射。 */
data class Mapping(
    val id: String,
    val name: String,
    val sourcePath: String,
)

/** 模组配置。 */
data class Config(
    var deviceId: String,
    var deviceName: String,
    var port: Int,
    var mappings: List<Mapping>,
    val trustedFingerprints: Map<String, String> = emptyMap(),
)

/** 本地配置读写（`~/.spw-send/config.json`）。 */
class ConfigStore(val directory: File = File(System.getProperty("user.home"), ".spw-send")) {

    private val dir = directory
    private val file: File = File(dir, "config.json")

    @Synchronized
    fun load(): Config {
        if (!file.exists()) {
            val fresh =
                Config(
                    deviceId = UUID.randomUUID().toString(),
                    deviceName = defaultDeviceName(),
                    port = Constants.DEFAULT_HTTP_PORT,
                    mappings = mutableListOf(),
                )
            save(fresh)
            return fresh
        }
        val parsed = Json.parse(file.readText(Charsets.UTF_8))
        require(parsed is Map<*, *>) { "配置文件损坏，请从备份恢复：${file.absolutePath}" }
        val data = parsed.asMap()
        val storedTrust = data["trustedFingerprints"]
        require(storedTrust == null || storedTrust is Map<*, *>) { "证书信任记录损坏" }
        val trusted =
            storedTrust.asMap().mapValues { (_, value) ->
                require(value is String && Regex("[a-f0-9]{64}").matches(value)) { "证书指纹记录损坏" }
                value
            }
        val storedPort = data.intValue("port")
        val needsMigration = data.intValue("configVersion") < Constants.CONFIG_VERSION
        val port =
            if (needsMigration && storedPort == Constants.LEGACY_HTTP_PORT) {
                Constants.DEFAULT_HTTP_PORT
            } else if (storedPort in 1024..65535) {
                storedPort
            } else {
                Constants.DEFAULT_HTTP_PORT
            }
        val mappings =
            data["mappings"].asList().mapNotNull { item ->
                val m = item.asMap()
                val id = m.str("id").ifEmpty { UUID.randomUUID().toString() }
                val name = m.str("name")
                val source = m.str("sourcePath")
                if (name.isEmpty() || source.isEmpty()) null else Mapping(id, name, source)
            }
        val config =
            Config(
                deviceId = data.str("deviceId").ifEmpty { UUID.randomUUID().toString() },
                deviceName = data.str("deviceName").ifEmpty { defaultDeviceName() },
                port = port,
                trustedFingerprints = trusted,
                mappings = mappings,
            )
        // 兼容旧配置：缺少设备 id 或需要迁移端口时回写。损坏文件不会覆盖信任记录。
        if (data.str("deviceId").isEmpty() || needsMigration) save(config)
        return config
    }

    @Synchronized
    fun save(config: Config) {
        try {
            saveStrict(config)
        } catch (_: Exception) {
            // 一般界面偏好保存失败仍允许本次使用；身份信任调用严格保存入口。
        }
    }

    @Synchronized
    fun saveStrict(config: Config) {
        if (!dir.exists()) dir.mkdirs()
        val payload =
            Json.obj(
                "configVersion" to Constants.CONFIG_VERSION,
                "trustedFingerprints" to config.trustedFingerprints,
                "deviceId" to config.deviceId,
                "deviceName" to config.deviceName,
                "port" to config.port,
                "mappings" to
                    config.mappings.map {
                        Json.obj(
                            "id" to it.id,
                            "name" to it.name,
                            "sourcePath" to it.sourcePath,
                        )
                    },
            )
        writeAtomically(file, Json.stringify(payload))
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // 部分文件系统（如网络盘）不支持原子移动，退化为普通替换。
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun defaultDeviceName(): String {
        val user = System.getProperty("user.name") ?: "PC"
        return "$user 的电脑"
    }
}
