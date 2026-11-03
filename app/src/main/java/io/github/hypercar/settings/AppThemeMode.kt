package io.github.hypercar.settings

enum class AppThemeMode(val value: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        fun fromValue(value: String?): AppThemeMode = entries.firstOrNull { it.value == value } ?: Dark
    }
}
