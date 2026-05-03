package com.oxclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OxPurple       = Color(0xFF7B2FBE)
val OxPurpleLight  = Color(0xFF9B59D6)
val OxPurpleDark   = Color(0xFF4A1080)
val OxBackground   = Color(0xFF0F0F14)
val OxSurface      = Color(0xFF1A1A24)
val OxSurfaceVar   = Color(0xFF22222F)
val OxOnBackground = Color(0xFFE8E8F0)
val OxOnSurface    = Color(0xFFD0D0E0)
val OxOutline      = Color(0xFF3A3A50)
val OxError        = Color(0xFFCF6679)

private val Scheme = darkColorScheme(
    primary          = OxPurple,
    onPrimary        = Color.White,
    primaryContainer = OxPurpleDark,
    secondary        = OxPurpleLight,
    background       = OxBackground,
    surface          = OxSurface,
    surfaceVariant   = OxSurfaceVar,
    onBackground     = OxOnBackground,
    onSurface        = OxOnSurface,
    outline          = OxOutline,
    error            = OxError
)

@Composable
fun OxClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
