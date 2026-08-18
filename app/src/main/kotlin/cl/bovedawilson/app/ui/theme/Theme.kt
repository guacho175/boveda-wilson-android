package cl.bovedawilson.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Paleta oficial orbynex.digital v2, tomada literalmente de
 * `_fuente/manual_orbynex.html` (sección 7, "Paleta cromática"). Ningún valor se
 * inventa: son los ocho tonos nombrados del manual más el tono de transición
 * `#0B2A6B` usado en el degradado oficial de portada (sección 9).
 */
private object OrbynexPalette {
    val DeepSpace = Color(0xFF071A3D)
    val OrbynexBlue = Color(0xFF1463FF)
    val ElectricCyan = Color(0xFF00D4FF)
    val NeuralIndigo = Color(0xFF4F46E5)
    val MagentaPulse = Color(0xFFD946EF)
    val SoftMagenta = Color(0xFFE879F9)
    val SoftCloud = Color(0xFFF5F8FF)
    val Graphite = Color(0xFF111827)
    val Line = Color(0xFFE2E8F4)
    val Muted = Color(0xFF5B6B8C)

    /** Paso intermedio del degradado de portada Deep Space → Orbynex Blue. */
    val DeepBlueTransition = Color(0xFF0B2A6B)

    /** Rojo de error convencional: el manual no define un color de error y prohíbe
     * expresamente el rojo como color dominante de marca; aquí no es marca, es un
     * estado funcional universal de la interfaz. */
    val ErrorDark = Color(0xFFCF6679)
    val ErrorLight = Color(0xFFB3261E)
}

/**
 * El fondo oscuro es el uso preferente de la marca (manual §4, §9): portadas, hero,
 * piezas de impacto. El acento fucsia/magenta se reserva para CTA y énfasis puntual,
 * nunca como color dominante (regla 70/20/10, manual §7).
 */
private val DarkColors = darkColorScheme(
    primary = OrbynexPalette.OrbynexBlue,
    onPrimary = Color.White,
    primaryContainer = OrbynexPalette.NeuralIndigo,
    onPrimaryContainer = OrbynexPalette.SoftCloud,
    secondary = OrbynexPalette.ElectricCyan,
    onSecondary = OrbynexPalette.DeepSpace,
    secondaryContainer = OrbynexPalette.DeepBlueTransition,
    onSecondaryContainer = OrbynexPalette.ElectricCyan,
    tertiary = OrbynexPalette.MagentaPulse,
    onTertiary = Color.White,
    tertiaryContainer = OrbynexPalette.SoftMagenta,
    onTertiaryContainer = OrbynexPalette.DeepSpace,
    background = OrbynexPalette.DeepSpace,
    onBackground = OrbynexPalette.SoftCloud,
    surface = OrbynexPalette.DeepSpace,
    onSurface = OrbynexPalette.SoftCloud,
    surfaceVariant = OrbynexPalette.DeepBlueTransition,
    onSurfaceVariant = OrbynexPalette.SoftCloud,
    outline = OrbynexPalette.Muted,
    error = OrbynexPalette.ErrorDark,
    onError = Color.Black
)

/** Fondos claros: documentos, cotizaciones y bloques de texto extenso (manual §9). */
private val LightColors = lightColorScheme(
    primary = OrbynexPalette.OrbynexBlue,
    onPrimary = Color.White,
    primaryContainer = OrbynexPalette.Line,
    onPrimaryContainer = OrbynexPalette.DeepSpace,
    secondary = OrbynexPalette.NeuralIndigo,
    onSecondary = Color.White,
    secondaryContainer = OrbynexPalette.Line,
    onSecondaryContainer = OrbynexPalette.NeuralIndigo,
    tertiary = OrbynexPalette.MagentaPulse,
    onTertiary = Color.White,
    tertiaryContainer = OrbynexPalette.SoftMagenta,
    onTertiaryContainer = OrbynexPalette.DeepSpace,
    background = OrbynexPalette.SoftCloud,
    onBackground = OrbynexPalette.Graphite,
    surface = OrbynexPalette.SoftCloud,
    onSurface = OrbynexPalette.Graphite,
    surfaceVariant = OrbynexPalette.Line,
    onSurfaceVariant = OrbynexPalette.Graphite,
    outline = OrbynexPalette.Muted,
    error = OrbynexPalette.ErrorLight,
    onError = Color.White
)

@Composable
fun BovedaWilsonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BovedaTypography,
        content = content
    )
}
