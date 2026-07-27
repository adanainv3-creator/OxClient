package com.nexoraclient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NexoraBackground     = Color(0xFFEAE7E0)
val NexoraSurface        = Color(0xFFE9E6DF)
val NexoraSurfaceVar     = Color(0xFFDDD9D0)
val NexoraSurfaceRaised  = Color(0xFFF8F6F1)

val NexoraAccent         = Color(0xFF1C1C1E)
val NexoraAccentLight    = Color(0xFF48484A)
val NexoraAccentDark     = Color(0xFFEAEAEC)

val NexoraOnBackground   = Color(0xFF1C1C1E)
val NexoraOnSurface      = Color(0xFF3A3A3C)
val NexoraOnSurfaceDim   = Color(0xFF8A8A8E)

val NexoraOutline        = Color(0xFFD5D5D9)
val NexoraOutlineStrong  = Color(0xFFB8B8BD)

val NexoraError          = Color(0xFF1C1C1E)
val NexoraSuccess        = Color(0xFF6E6E73)
val NexoraWarning        = Color(0xFF8A8A8E)

val NexoraConnectIdle    = Color(0xFFC7C7CC)

// Alt gezinme çubuğu için açık ten rengi
val NexoraSkinTone       = Color(0xFFF1D7B8)

// Aktif/açık modül kartları için grimsi tonlar (yeşil yerine)
val NexoraModuleActive       = Color(0xFFAEAEB4)
val NexoraModuleActiveBorder = Color(0xFF7D7D83)
val NexoraModuleExpanded     = Color(0xFF9C9CA3)
val NexoraModuleActiveText   = Color(0xFF1C1C1E)

val NexoraPurple      = NexoraAccent
val NexoraPurpleLight = NexoraAccentLight
val NexoraPurpleDark  = NexoraAccentDark

private val Scheme = lightColorScheme(
    primary          = NexoraAccent,
    onPrimary        = Color.White,
    primaryContainer = NexoraAccentDark,
    secondary        = NexoraAccentLight,
    background       = NexoraBackground,
    surface          = NexoraSurface,
    surfaceVariant   = NexoraSurfaceVar,
    onBackground     = NexoraOnBackground,
    onSurface        = NexoraOnSurface,
    outline          = NexoraOutline,
    error            = NexoraError
)

@Composable
fun NexoraClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
