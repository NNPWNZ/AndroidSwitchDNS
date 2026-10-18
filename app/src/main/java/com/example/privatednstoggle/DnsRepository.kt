package com.example.privatednstoggle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Режимы работы Частного DNS (без автоматического режима opportunistic по требованию).
 */
enum class PrivateDnsMode(val settingsValue: String, val titleRu: String) {
    OFF("off", "Выкл."),
    HOSTNAME("hostname", "Имя хоста провайдера DNS");

    companion object {
        fun fromSettingsValue(value: String?): PrivateDnsMode {
            return if (value == HOSTNAME.settingsValue) HOSTNAME else OFF
        }
    }
}

/**
 * Модель сохраненного DNS-сервера с меткой времени последнего использования
 * для быстрого выбора недавних серверов.
 */
data class SavedDnsServer(
    val id: String,
    val name: String,
    val hostname: String,
    val lastUsedAt: Long
)

/**
 * Текущее состояние системного Частного DNS.
 */
data class PrivateDnsState(
    val hasPermission: Boolean,
    val mode: PrivateDnsMode,
    val currentHostname: String,
    val savedServers: List<SavedDnsServer>
)

class DnsRepository(private val context: Context) {

    companion object {
        const val PRIVATE_DNS_MODE_KEY = "private_dns_mode"
        const val PRIVATE_DNS_SPECIFIER_KEY = "private_dns_specifier"

        private const val PREFS_NAME = "private_dns_prefs"
        private const val KEY_SAVED_SERVERS = "saved_dns_servers"
        private const val KEY_LAST_HOSTNAME = "last_used_hostname"
        private const val KEY_INITIALIZED = "defaults_initialized"

        private val HOSTNAME_REGEX = Regex(
            "^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$"
        )

        fun isValidHostname(host: String): Boolean {
            val trimmed = host.trim()
            return trimmed.isNotEmpty() && trimmed.length <= 253 && HOSTNAME_REGEX.matches(trimmed)
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val contentResolver = context.contentResolver

    init {
        ensureDefaultsInitialized()
    }

    /**
     * Проверяет наличие разрешения WRITE_SECURE_SETTINGS.
     */
    fun hasWriteSecureSettingsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Читает текущий режим из системных настроек Settings.Global.
     */
    fun getSystemDnsMode(): PrivateDnsMode {
        val rawMode = Settings.Global.getString(contentResolver, PRIVATE_DNS_MODE_KEY)
        return PrivateDnsMode.fromSettingsValue(rawMode)
    }

    /**
     * Читает текущий хост провайдера из Settings.Global или возвращает последний использованный.
     */
    fun getActiveOrLastHostname(): String {
        val systemHost = Settings.Global.getString(contentResolver, PRIVATE_DNS_SPECIFIER_KEY)?.trim()
        if (!systemHost.isNullOrEmpty()) {
            return systemHost
        }
        val savedLast = prefs.getString(KEY_LAST_HOSTNAME, null)
        if (!savedLast.isNullOrEmpty()) {
            return savedLast
        }
        return getSavedServers().firstOrNull()?.hostname ?: "dns.adguard-dns.com"
    }

    /**
     * Возвращает полное состояние для отображения в UI и плитке быстрых настроек.
     */
    fun getState(): PrivateDnsState {
        return PrivateDnsState(
            hasPermission = hasWriteSecureSettingsPermission(),
            mode = getSystemDnsMode(),
            currentHostname = getActiveOrLastHostname(),
            savedServers = getSavedServers()
        )
    }

    /**
     * Включает режим "Имя хоста провайдера DNS" и устанавливает указанный хост.
     * Обновляет время последнего использования для сортировки в "Недавних".
     */
    fun enablePrivateDns(hostname: String, customLabel: String? = null): Result<Unit> {
        val cleanHost = hostname.trim().lowercase()
        if (!isValidHostname(cleanHost)) {
            return Result.failure(IllegalArgumentException("Некорректный формат имени хоста DNS"))
        }
        if (!hasWriteSecureSettingsPermission()) {
            return Result.failure(SecurityException("Отсутствует разрешение WRITE_SECURE_SETTINGS"))
        }

        return try {
            Settings.Global.putString(contentResolver, PRIVATE_DNS_SPECIFIER_KEY, cleanHost)
            Settings.Global.putString(
                contentResolver,
                PRIVATE_DNS_MODE_KEY,
                PrivateDnsMode.HOSTNAME.settingsValue
            )
            prefs.edit().putString(KEY_LAST_HOSTNAME, cleanHost).apply()
            upsertServerUsage(cleanHost, customLabel)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Выключает Частный DNS (режим "off"), сохраняя последний хост для быстрого включения.
     */
    fun disablePrivateDns(): Result<Unit> {
        if (!hasWriteSecureSettingsPermission()) {
            return Result.failure(SecurityException("Отсутствует разрешение WRITE_SECURE_SETTINGS"))
        }
        return try {
            val currentSpec = Settings.Global.getString(contentResolver, PRIVATE_DNS_SPECIFIER_KEY)
            if (!currentSpec.isNullOrBlank()) {
                prefs.edit().putString(KEY_LAST_HOSTNAME, currentSpec.trim()).apply()
            }
            Settings.Global.putString(
                contentResolver,
                PRIVATE_DNS_MODE_KEY,
                PrivateDnsMode.OFF.settingsValue
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Переключает состояние между "Выкл." и "Имя хоста провайдера DNS".
     * Используется плиткой быстрых настроек (TileService).
     */
    fun togglePrivateDns(): Result<PrivateDnsMode> {
        val currentMode = getSystemDnsMode()
        return if (currentMode == PrivateDnsMode.HOSTNAME) {
            disablePrivateDns().map { PrivateDnsMode.OFF }
        } else {
            val targetHost = getActiveOrLastHostname()
            enablePrivateDns(targetHost).map { PrivateDnsMode.HOSTNAME }
        }
    }

    /**
     * Сохраняет новый DNS-сервер в список (или обновляет существующий) без обязательного включения.
     */
    fun saveDnsServer(hostname: String, label: String): Result<SavedDnsServer> {
        val cleanHost = hostname.trim().lowercase()
        if (!isValidHostname(cleanHost)) {
            return Result.failure(IllegalArgumentException("Введите корректное доменное имя (например, dns.adguard-dns.com)"))
        }
        val cleanLabel = label.trim().ifEmpty { cleanHost }
        val list = getSavedServers().toMutableList()
        val now = System.currentTimeMillis()
        val existingIndex = list.indexOfFirst { it.hostname.equals(cleanHost, ignoreCase = true) }

        val updatedItem = if (existingIndex >= 0) {
            val old = list[existingIndex]
            old.copy(name = cleanLabel, hostname = cleanHost, lastUsedAt = now).also {
                list[existingIndex] = it
            }
        } else {
            SavedDnsServer(
                id = cleanHost,
                name = cleanLabel,
                hostname = cleanHost,
                lastUsedAt = now
            ).also { list.add(0, it) }
        }

        persistServers(list)
        return Result.success(updatedItem)
    }

    /**
     * Удаляет DNS-сервер из списка сохраненных.
     */
    fun deleteDnsServer(hostname: String) {
        val filtered = getSavedServers().filterNot {
            it.hostname.equals(hostname.trim(), ignoreCase = true)
        }
        persistServers(filtered)
    }

    /**
     * Возвращает список сохраненных DNS-серверов, отсортированный по времени последнего использования
     * (последние использованные всегда сверху для быстрого выбора).
     */
    fun getSavedServers(): List<SavedDnsServer> {
        val rawJson = prefs.getString(KEY_SAVED_SERVERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(rawJson)
            val result = mutableListOf<SavedDnsServer>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val host = obj.optString("hostname", "").trim()
                if (host.isNotEmpty()) {
                    result.add(
                        SavedDnsServer(
                            id = obj.optString("id", host),
                            name = obj.optString("name", host),
                            hostname = host,
                            lastUsedAt = obj.optLong("lastUsedAt", 0L)
                        )
                    )
                }
            }
            result.sortedByDescending { it.lastUsedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun upsertServerUsage(hostname: String, customLabel: String?) {
        val list = getSavedServers().toMutableList()
        val now = System.currentTimeMillis()
        val index = list.indexOfFirst { it.hostname.equals(hostname, ignoreCase = true) }
        if (index >= 0) {
            val item = list[index]
            list[index] = item.copy(
                name = customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: item.name,
                lastUsedAt = now
            )
        } else {
            list.add(
                SavedDnsServer(
                    id = hostname,
                    name = customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: hostname,
                    hostname = hostname,
                    lastUsedAt = now
                )
            )
        }
        persistServers(list)
    }

    private fun persistServers(servers: List<SavedDnsServer>) {
        val array = JSONArray()
        servers.sortedByDescending { it.lastUsedAt }.forEach { server ->
            val obj = JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("hostname", server.hostname)
                put("lastUsedAt", server.lastUsedAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_SAVED_SERVERS, array.toString()).apply()
    }

    private fun ensureDefaultsInitialized() {
        if (prefs.getBoolean(KEY_INITIALIZED, false)) return
        val now = System.currentTimeMillis()
        val defaults = listOf(
            SavedDnsServer("dns.adguard-dns.com", "AdGuard DNS", "dns.adguard-dns.com", now - 1000),
            SavedDnsServer("one.one.one.one", "Cloudflare DNS", "one.one.one.one", now - 2000),
            SavedDnsServer("dns.quad9.net", "Quad9 DNS", "dns.quad9.net", now - 3000),
            SavedDnsServer("dns.google", "Google Public DNS", "dns.google", now - 4000)
        )
        persistServers(defaults)
        prefs.edit()
            .putString(KEY_LAST_HOSTNAME, "dns.adguard-dns.com")
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }
}
