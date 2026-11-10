package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ExportQualityPreset
import com.example.model.FilterType
import com.example.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "docusnap_settings")

data class AppSettings(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val defaultDocumentFilter: FilterType = FilterType.ENHANCED,
    val defaultExportQuality: ExportQualityPreset = ExportQualityPreset.HIGH,
    val autoCaptureEnabled: Boolean = true
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_preference")
        val DEFAULT_FILTER = stringPreferencesKey("default_document_filter")
        val EXPORT_QUALITY = stringPreferencesKey("default_export_quality")
        val AUTO_CAPTURE = booleanPreferencesKey("auto_capture_enabled")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val theme = prefs[Keys.THEME]?.let {
            runCatching { ThemePreference.valueOf(it) }.getOrNull()
        } ?: ThemePreference.SYSTEM

        val filter = prefs[Keys.DEFAULT_FILTER]?.let {
            runCatching { FilterType.valueOf(it) }.getOrNull()
        } ?: FilterType.ENHANCED

        val quality = prefs[Keys.EXPORT_QUALITY]?.let {
            runCatching { ExportQualityPreset.valueOf(it) }.getOrNull()
        } ?: ExportQualityPreset.HIGH

        val autoCapture = prefs[Keys.AUTO_CAPTURE] ?: true

        AppSettings(
            themePreference = theme,
            defaultDocumentFilter = filter,
            defaultExportQuality = quality,
            autoCaptureEnabled = autoCapture
        )
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.settingsDataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setDefaultDocumentFilter(filter: FilterType) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_FILTER] = filter.name }
    }

    suspend fun setDefaultExportQuality(quality: ExportQualityPreset) {
        context.settingsDataStore.edit { it[Keys.EXPORT_QUALITY] = quality.name }
    }

    suspend fun setAutoCaptureEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_CAPTURE] = enabled }
    }
}
