package com.pianotype.android.config

import android.content.Context

/**
 * 見た目の設定（テーマカラー・背景色）を SharedPreferences に保存する。
 * TOML とは独立。
 */
object AppPreferences {
    private const val PREF = "pianotype_prefs"
    private const val KEY_THEME = "theme_color"
    private const val KEY_BG = "bg_color"

    const val DEFAULT_THEME: Int = 0xFF7E57C2.toInt()      // 紫
    const val DEFAULT_BG_LIGHT: Int = 0xFFFFFFFF.toInt()
    const val DEFAULT_BG_DARK: Int = 0xFF121212.toInt()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getThemeColor(ctx: Context): Int =
        prefs(ctx).getInt(KEY_THEME, DEFAULT_THEME)

    fun setThemeColor(ctx: Context, argb: Int) {
        prefs(ctx).edit().putInt(KEY_THEME, argb).apply()
    }

    fun getBackgroundColor(ctx: Context, dark: Boolean): Int {
        val def = if (dark) DEFAULT_BG_DARK else DEFAULT_BG_LIGHT
        return prefs(ctx).getInt(KEY_BG, def)
    }

    fun setBackgroundColor(ctx: Context, argb: Int) {
        prefs(ctx).edit().putInt(KEY_BG, argb).apply()
    }
}