package cl.bovedawilson.core.crypto.wrap

/** Keyset cifrado de Tink que envuelve la VDEK por un camino de acceso. Bytes opacos:
 * ni el contenido de la VDEK ni el de la KEK son legibles sin la KEK correcta. */
@JvmInline
value class WrappedVdek(val bytes: ByteArray)
