# Módulos y dependencias

**Versión:** 1.0 · **Actualizado:** 2026-08-17 · **Fuentes:** `settings.gradle.kts`, módulos Gradle y `docs/architecture.md`.

```mermaid
flowchart TB
    APP[":app\nCompose · navegación · ViewModels"]
    MODEL[":core:model\nmodelos descifrados en memoria"]
    COMMON[":core:common\nresultado · borrado · registro seguro"]
    SYNC[":data:sync\nsesión · repositorios · sync · backup"]
    CRYPTO[":core:crypto\nTink · Argon2id · HKDF · Keystore"]
    LOCAL[":data:local\nRoom · ciphertext"]
    REMOTE[":data:remote\nFirebase · ciphertext"]
    APP --> MODEL
    APP --> COMMON
    APP --> SYNC
    SYNC --> MODEL
    SYNC --> COMMON
    SYNC --> CRYPTO
    SYNC --> LOCAL
    SYNC --> REMOTE
    LOCAL --> COMMON
    LOCAL --> CRYPTO
    REMOTE --> COMMON
    REMOTE --> CRYPTO
    CRYPTO --> COMMON
```

`app` no depende directamente de los adaptadores locales/remotos ni del núcleo criptográfico. `data:local` y `data:remote` no dependen de `core:model`: sus APIs persisten y transmiten `Ciphertext` opaco, no notas descifradas.
