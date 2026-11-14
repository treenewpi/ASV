package com.icode.asmviewer.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage { System, Chinese, English }

object AppLocale {
    private const val PREFS = "asm_viewer_prefs"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_ADDRESS_KIND = "address_kind"

    fun storedLanguage(context: Context): AppLanguage =
        when (prefs(context).getString(KEY_LANGUAGE, "system")) {
            "zh-CN", "zh" -> AppLanguage.Chinese
            "en" -> AppLanguage.English
            else -> AppLanguage.System
        }

    fun applyStored(context: Context) {
        apply(context, storedLanguage(context))
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        val tag = when (language) {
            AppLanguage.System -> "system"
            AppLanguage.Chinese -> "zh-CN"
            AppLanguage.English -> "en"
        }
        prefs(context).edit().putString(KEY_LANGUAGE, tag).commit()
        apply(context, language)
    }

    fun apply(context: Context, language: AppLanguage) {
        val tags = when (language) {
            AppLanguage.Chinese -> "zh-CN"
            AppLanguage.English -> "en"
            AppLanguage.System -> ""
        }
        val localesCompat = if (tags.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tags)
        }
        // API 33+ stores per-app locales in LocaleManager. AppCompatDelegate alone
        // did not persist them on this device (API 37), so set both paths.
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = context.applicationContext.getSystemService(LocaleManager::class.java)
            lm.applicationLocales = if (tags.isEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tags)
            }
        }
        AppCompatDelegate.setApplicationLocales(localesCompat)
    }

    fun storedAddressKind(context: Context): AddressKind =
        when (prefs(context).getString(KEY_ADDRESS_KIND, "rva")) {
            "file" -> AddressKind.FileOffset
            "va" -> AddressKind.Va
            else -> AddressKind.Rva
        }

    fun storeAddressKind(context: Context, kind: AddressKind) {
        val tag = when (kind) {
            AddressKind.Rva -> "rva"
            AddressKind.FileOffset -> "file"
            AddressKind.Va -> "va"
        }
        prefs(context).edit().putString(KEY_ADDRESS_KIND, tag).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
