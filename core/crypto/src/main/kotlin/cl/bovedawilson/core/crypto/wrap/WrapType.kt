package cl.bovedawilson.core.crypto.wrap

/** Camino de acceso que envuelve la VDEK (`CRYPTOGRAPHY.md` §7). */
enum class WrapType(val canonical: String) {
    PASSWORD("password"),
    RECOVERY("recovery"),
    BIOMETRIC("biometric"),
}
