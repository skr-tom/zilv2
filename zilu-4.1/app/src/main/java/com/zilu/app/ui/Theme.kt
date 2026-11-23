package com.zilu.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 一组配色：accent 用于文字/边框，container 用于卡片底色。 */
data class ToneStyle(val accent: Color, val container: Color, val name: String)

/** 日历待办的柔和配色，对应参考图里的蓝紫 / 粉 / 橙 / 绿卡片。 */
val TodoPalette = listOf(
    ToneStyle(Color(0xFF6C7CF5), Color(0xFFE9EBFF), "蓝紫"),
    ToneStyle(Color(0xFFEE7B8E), Color(0xFFFFE8EC), "珊瑚粉"),
    ToneStyle(Color(0xFFEF9C43), Color(0xFFFFF0DF), "蜜橙"),
    ToneStyle(Color(0xFF3FB58C), Color(0xFFE1F6EE), "薄荷绿"),
    ToneStyle(Color(0xFF9A7BE0), Color(0xFFF0EBFF), "紫罗兰"),
    ToneStyle(Color(0xFF4AA3D8), Color(0xFFE2F1FB), "天青")
)

/** 文件夹配色：不同文件夹整体色调不同。 */
val FolderPalette = listOf(
    ToneStyle(Color(0xFFB8860B), Color(0xFFFFF5DE), "琥珀"),
    ToneStyle(Color(0xFF2B6CB0), Color(0xFFE6F0FB), "海蓝"),
    ToneStyle(Color(0xFF2F855A), Color(0xFFE3F5EB), "松绿"),
    ToneStyle(Color(0xFFB83280), Color(0xFFFBE7F2), "玫瑰"),
    ToneStyle(Color(0xFFC05621), Color(0xFFFDEEE2), "橙橘"),
    ToneStyle(Color(0xFF6B46C1), Color(0xFFEEE9FB), "紫罗兰"),
    ToneStyle(Color(0xFF00838F), Color(0xFFE0F4F6), "青蓝"),
    ToneStyle(Color(0xFF4A5568), Color(0xFFEDEFF3), "石板")
)

fun todoTone(index: Int): ToneStyle = TodoPalette[Math.floorMod(index, TodoPalette.size)]
fun folderTone(index: Int): ToneStyle = FolderPalette[Math.floorMod(index, FolderPalette.size)]

val DoneGreen = Color(0xFF2E9E68)
val UndoneRed = Color(0xFFD05555)
val WarnAmber = Color(0xFFC48712)

private data class ThemeSpec(val light: Color, val dark: Color)

private fun spec(name: String) = when (name) {
    "松石绿" -> ThemeSpec(Color(0xFF0F6F5C), Color(0xFF6FD3BA))
    "暮光紫" -> ThemeSpec(Color(0xFF6A4FA8), Color(0xFFBFAAF0))
    else -> ThemeSpec(Color(0xFFB07C10), Color(0xFFF0C766))
}

fun ziluColors(dark: Boolean, themeName: String): ColorScheme {
    val s = spec(themeName)
    return if (dark) darkColorScheme(
        primary = s.dark,
        onPrimary = Color(0xFF1B1B1B),
        primaryContainer = Color(0xFF3A3226),
        onPrimaryContainer = Color(0xFFF5E6C8),
        secondary = Color(0xFFB9A98C),
        background = Color(0xFF111110),
        onBackground = Color(0xFFEDEAE4),
        surface = Color(0xFF191917),
        onSurface = Color(0xFFEDEAE4),
        surfaceVariant = Color(0xFF26241F),
        onSurfaceVariant = Color(0xFFC9C3B8),
        outline = Color(0xFF6E6A62),
        error = Color(0xFFFF8A80)
    ) else lightColorScheme(
        primary = s.light,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFF1D6),
        onPrimaryContainer = Color(0xFF3A2C08),
        secondary = Color(0xFF6E6252),
        background = Color(0xFFFDFBF7),
        onBackground = Color(0xFF1D1B16),
        surface = Color(0xFFFFFDFA),
        onSurface = Color(0xFF1D1B16),
        surfaceVariant = Color(0xFFF3EEE3),
        onSurfaceVariant = Color(0xFF544E43),
        outline = Color(0xFF9A9284),
        error = Color(0xFFB3261E)
    )
}
