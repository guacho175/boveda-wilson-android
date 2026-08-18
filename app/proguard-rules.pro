# Reglas de R8 para :app — Bóveda Wilson
#
# El shrinking y la ofuscación se hacen **una sola vez y solo aquí**: los módulos de
# biblioteca tienen `isMinifyEnabled = false` (ver ADR pendiente y el hallazgo M-3 del
# informe de la Fase 1). Una biblioteca que necesite imponer una regla a su consumidor
# usa `consumerProguardFiles`, nunca `proguardFiles`.
#
# Este archivo nace deliberadamente sin reglas de `-keep`. Cada regla que se añada debe
# llevar encima la línea que explique **qué se rompe sin ella**: un `-keep` sin motivo
# escrito es minificación desactivada en silencio.
#
# Reglas previsibles en fases posteriores (todavía NO necesarias):
#   - Fase 2: Tink y BouncyCastle acceden por reflexión a sus registros de primitivas.
#   - Fase 3: las entidades de Room y sus DAO generados.
#   - Fase 7: Hilt y kotlinx.serialization.
# Cada una se añadirá en la fase que la necesite, con el fallo real que la justifica.
#
# Fase 4 (2026-07-31, ADR-037): FirestoreVaultSourceImpl lee cada campo del documento a
# mano (getLong/getString/getBlob) en vez de DocumentSnapshot.toObject(...), así que los
# DTO de firestore/FirestoreVaultSource.kt no se deserializan por reflexión y no
# necesitan `-keep`. `.\gradlew.bat assembleRelease` (2026-07-31, con firebase-auth y
# firebase-firestore ya en el grafo de compilación vía :data:sync → :data:remote) no
# generó `missing_rules.txt`; las reglas del consumidor de los SDK de Firebase
# (`consumerProguardFiles`) bastaron.

# Conserva los números de línea para que un informe de fallo operativo sea legible sin
# revelar nombres originales de clase.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
