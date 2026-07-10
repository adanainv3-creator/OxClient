package com.oxclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OxBackground     = Color(0xFF0E1020)
val OxSurface        = Color(0xFF1E2340)
val OxSurfaceVar     = Color(0xFF2D3561)
val OxSurfaceRaised  = Color(0xFF3A4580)

val OxAccent         = Color(0xFF4A5899)
val OxAccentLight    = Color(0xFF6B7EC2)
val OxAccentDark     = Color(0xFF1A1F3A)

val OxOnBackground   = Color(0xFFDDE2F0)
val OxOnSurface      = Color(0xFFB8C0D8)
val OxOnSurfaceDim   = Color(0xFF6B7499)

val OxOutline        = Color(0xFF2D3561)
val OxOutlineStrong  = Color(0xFF3A4580)

val OxError          = Color(0xFFB1423C)
val OxSuccess        = Color(0xFF3F9A5B)
val OxWarning        = Color(0xFFB8863B)

val OxConnectIdle    = Color(0xFF2D3561)

val OxPurple      = OxAccent
val OxPurpleLight = OxAccentLight
val OxPurpleDark  = OxAccentDark

private val Scheme = darkColorScheme(
    primary          = OxAccent,
    onPrimary        = Color.White,
    primaryContainer = OxAccentDark,
    secondary        = OxAccentLight,
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
