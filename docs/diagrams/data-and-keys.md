# Datos y ciclo de claves

**Versión:** 1.0 · **Actualizado:** 2026-08-17 · **Fuentes:** `CRYPTOGRAPHY.md`, `docs/data-flow.md` y `docs/key-lifecycle.md`.

```mermaid
flowchart LR
    MP["Contraseña maestra\nsolo memoria"] --> ARG["Argon2id"] --> PK["PasswordKEK\ntransitoria"]
    RP["24 palabras\nsolo memoria"] --> ENT["Entropía BIP-39"] --> HKDF["HKDF"] --> RK["RecoveryKEK\ntransitoria"]
    PK --> WRAP["VDEK envuelta"]
    RK --> WRAP
    KEY["Android Keystore"] --> BIO["BiometricKEK envuelta"] --> WRAP
    WRAP --> VDEK["VDEK\ncapacidad UnlockedVault"]
    ITEM["VaultItem\nplaintext en memoria"] --> AEAD["Tink AEAD\nAES-256-GCM + AAD"]
    VDEK --> AEAD
    AEAD --> CT["Ciphertext opaco"]
    CT --> ROOM["Room BLOB"]
    CT --> FIRESTORE["Firestore bytes"]
    CT --> BACKUP["Respaldo cifrado"]
```

El servidor puede ver identificadores aleatorios, versiones, tamaños y marcas de tiempo, pero no contenido descifrado. La biometría es local al dispositivo y no forma parte de Firestore ni del respaldo.
