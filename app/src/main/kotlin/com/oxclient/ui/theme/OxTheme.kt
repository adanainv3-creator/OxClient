package com.oxclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OxBackground     = Color(0xFFEAE7E0)
val OxSurface        = Color(0xFFE9E6DF)
val OxSurfaceVar     = Color(0xFFDDD9D0)
val OxSurfaceRaised  = Color(0xFFF8F6F1)

val OxAccent         = Color(0xFF1C1C1E)
val OxAccentLight    = Color(0xFF48484A)
val OxAccentDark     = Color(0xFFEAEAEC)

val OxOnBackground   = Color(0xFF1C1C1E)
val OxOnSurface      = Color(0xFF3A3A3C)
val OxOnSurfaceDim   = Color(0xFF8A8A8E)

val OxOutline        = Color(0xFFD5D5D9)
val OxOutlineStrong  = Color(0xFFB8B8BD)

val OxError          = Color(0xFF1C1C1E)
val OxSuccess        = Color(0xFF6E6E73)
val OxWarning        = Color(0xFF8A8A8E)

val OxConnectIdle    = Color(0xFFC7C7CC)

// Aktif/açık modül kartları için grimsi tonlar (yeşil yerine)
val OxModuleActive       = Color(0xFFAEAEB4)
val OxModuleActiveBorder = Color(0xFF7D7D83)
val OxModuleExpanded     = Color(0xFF9C9CA3)
val OxModuleActiveText   = Color(0xFF1C1C1E)

val OxPurple      = OxAccent
val OxPurpleLight = OxAccentLight
val OxPurpleDark  = OxAccentDark

private val Scheme = lightColorScheme(
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
