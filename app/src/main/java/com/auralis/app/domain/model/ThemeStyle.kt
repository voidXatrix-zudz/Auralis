package com.auralis.app.domain.model

/**
 * Visual style of the whole app. Independent from [ThemeMode] (dark/light), which only applies to NORMAL.
 * NORMAL is the default so existing installs keep their current look.
 */
enum class ThemeStyle { NORMAL, GLASSMORPHIC }
