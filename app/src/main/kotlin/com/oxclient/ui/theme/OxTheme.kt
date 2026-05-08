package com.oxclient.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Renk paleti ───────────────────────────────────────────────────────────
val OxPurple        = Color(0xFF7B2FBE)
val OxPurpleLight   = Color(0xFF9B59D6)
val OxPurpleDark    = Color(0xFF5A1E9A)
val OxBackground    = Color(0xFF0D0D14)
val OxSurface       = Color(0xFF1A1A26)
val OxSurface2      = Color(0xFF222233)
val OxBorder        = Color(0xFF2E2E44)
val OxText          = Color(0xFFE8E8F0)
val OxTextSub       = Color(0xFF8888AA)
val OxGreen         = Color(0xFF2ECC71)
val OxRed           = Color(0xFFE74C3C)
val OxYellow        = Color(0xFFF39C12)
val OxCyan          = Color(0xFF1ABC9C)

private val DarkColorScheme = darkColorScheme(
    primary          = OxPurple,
    onPrimary        = Color.White,
    primaryContainer = OxPurpleDark,
    secondary        = OxPurpleLight,
    onSecondary      = Color.White,
    background       = OxBackground,
    onBackground     = OxText,
    surface          = OxSurface,
    onSurface        = OxText,
    surfaceVariant   = OxSurface2,
    onSurfaceVariant = OxTextSub,
    outline          = OxBorder,
    error            = OxRed,
    onError          = Color.White,
)

@Composable
fun OxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content
    )
}
