package com.alpineterminal

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "axiom_secure_settings",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _themeMode = MutableStateFlow(getThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _terminalFontSize = MutableStateFlow(getFontSize())
    val terminalFontSize: StateFlow<Int> = _terminalFontSize.asStateFlow()

    private val _githubToken = MutableStateFlow(getGithubToken())
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    enum class ThemeMode { LIGHT, DARK, SYSTEM }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    fun setFontSize(size: Int) {
        prefs.edit().putInt("font_size", size).apply()
        _terminalFontSize.value = size
    }

    fun setGithubToken(token: String) {
        prefs.edit().putString("github_token", token).apply()
        _githubToken.value = token
    }

    private fun getThemeMode(): ThemeMode {
        val mode = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        return ThemeMode.valueOf(mode ?: ThemeMode.SYSTEM.name)
    }

    private fun getFontSize(): Int {
        return prefs.getInt("font_size", 14)
    }

    private fun getGithubToken(): String {
        return prefs.getString("github_token", "") ?: ""
    }
}
