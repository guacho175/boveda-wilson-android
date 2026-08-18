package cl.bovedawilson.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * El manual de marca (`_fuente/manual_orbynex.html` §10) pide Montserrat para
 * títulos e Inter para interfaz y texto largo, con la propia alternativa segura
 * documentada por el manual para cuando esas fuentes no están disponibles:
 * "Aptos Display / Arial Bold" y "Aptos / Arial". Sin dependencias de red ni
 * fuentes remotas (restricción explícita), se usa la fuente del sistema
 * (`FontFamily.Default`, Roboto en Android) como esa alternativa segura, y se
 * reproduce la jerarquía de peso y tracking del manual: títulos con peso alto y
 * mayor espaciado entre letras, cuerpo regular.
 */
private val TitleFamily = FontFamily.Default
private val BodyFamily = FontFamily.Default

val BovedaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.Black,
        fontSize = 57.sp,
        letterSpacing = 0.5.sp
    ),
    displayMedium = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 45.sp,
        letterSpacing = 0.5.sp
    ),
    displaySmall = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    titleSmall = TextStyle(
        fontFamily = TitleFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)
