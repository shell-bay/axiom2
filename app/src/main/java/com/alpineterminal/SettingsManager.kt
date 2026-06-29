package com.alpineterminal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SshConnection(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val lastUsed: Long = 0L
)

enum class AuthType { PASSWORD, KEY }

class SettingsManager(context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "axiom_secure_settings",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val gson = Gson()

    private val _themeMode = MutableStateFlow(getThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _terminalFontSize = MutableStateFlow(getFontSize())
    val terminalFontSize: StateFlow<Int> = _terminalFontSize.asStateFlow()

    private val _githubToken = MutableStateFlow(getGithubToken())
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    private val _sshConnections = MutableStateFlow(getSshConnections())
    val sshConnections: StateFlow<List<SshConnection>> = _sshConnections.asStateFlow()

    private val _sshPrivateKey = MutableStateFlow(getSshPrivateKey())
    val sshPrivateKey: StateFlow<String> = _sshPrivateKey.asStateFlow()

    enum class ThemeMode { LIGHT, DARK, SYSTEM }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    fun setFontSize(size: Int) {
        prefs.edit().putInt("font_size", size.coerceIn(10, 24)).apply()
        _terminalFontSize.value = size.coerceIn(10, 24)
    }

    fun adjustFontSize(delta: Int) {
        setFontSize((_terminalFontSize.value + delta).coerceIn(10, 24))
    }

    fun setGithubToken(token: String) {
        prefs.edit().putString("github_token", token).apply()
        _githubToken.value = token
    }

    fun saveSshConnection(conn: SshConnection) {
        val list = _sshConnections.value.toMutableList()
        val idx = list.indexOfFirst { it.id == conn.id }
        if (idx >= 0) list[idx] = conn else list.add(conn)
        _sshConnections.value = list
        saveSshConnections(list)
    }

    fun deleteSshConnection(id: String) {
        val list = _sshConnections.value.filter { it.id != id }
        _sshConnections.value = list
        saveSshConnections(list)
    }

    fun setSshPrivateKey(key: String) {
        prefs.edit().putString("ssh_private_key", key).apply()
        _sshPrivateKey.value = key
    }

    private fun saveSshConnections(list: List<SshConnection>) {
        prefs.edit().putString("ssh_connections", gson.toJson(list)).apply()
    }

    private fun getThemeMode(): ThemeMode {
        return try {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) { ThemeMode.SYSTEM }
    }

    private fun getFontSize(): Int = prefs.getInt("font_size", 14).coerceIn(10, 24)

    private fun getGithubToken(): String = prefs.getString("github_token", "") ?: ""

    private fun getSshConnections(): List<SshConnection> {
        val json = prefs.getString("ssh_connections", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SshConnection>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun getSshPrivateKey(): String = prefs.getString("ssh_private_key", "") ?: ""
}
