package cl.bovedawilson.app.ui.screens

/** Agrupa los tres booleanos de estado de la sección de biometría de `SettingsScreen`
 * para no exceder el límite de parámetros por función de Detekt. */
internal data class BiometricSectionState(
    val hardwareAvailable: Boolean,
    val enrolled: Boolean,
    val isLoading: Boolean
)
