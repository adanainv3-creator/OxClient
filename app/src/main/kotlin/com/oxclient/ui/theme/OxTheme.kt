package com.oxclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// --- Core palette: dark graphite / steel blue. No purple, no pastel. ---
val OxBackground     = Color(0xFF0A0C10)
val OxSurface        = Color(0xFF12151B)
val OxSurfaceVar     = Color(0xFF191D25)
val OxSurfaceRaised  = Color(0xFF1F2430)

val OxAccent         = Color(0xFF3E6E9E)   // steel blue - main accent
val OxAccentLight    = Color(0xFF5C8CBF)
val OxAccentDark     = Color(0xFF1C3247)

val OxOnBackground   = Color(0xFFE7EAEE)
val OxOnSurface      = Color(0xFFA6ACB8)
val OxOnSurfaceDim   = Color(0xFF6B7280)

val OxOutline        = Color(0xFF272C36)
val OxOutlineStrong  = Color(0xFF39404D)

val OxError          = Color(0xFFB1423C)
val OxSuccess        = Color(0xFF3F9A5B)
val OxWarning        = Color(0xFFB8863B)

// --- Legacy aliases kept only so older files (e.g. OverlayService.kt) still
// --- compile without edits. They now point at the steel-blue palette above,
// --- not an actual purple color.
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