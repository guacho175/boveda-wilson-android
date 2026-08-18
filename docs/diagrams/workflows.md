# Flujos operativos

**Versión:** 1.0 · **Actualizado:** 2026-08-17 · **Fuentes:** `docs/sync-protocol.md`, `BACKUP_FORMAT.md` y código de `:data:sync`.

## Desbloqueo y edición

```mermaid
sequenceDiagram
    actor U as Usuario
    participant UI as Interfaz
    participant S as VaultSession
    participant C as Núcleo criptográfico
    participant R as Repositorio
    U->>UI: Contraseña o biometría
    UI->>S: Solicita desbloqueo
    S->>C: Desenvuelve VDEK localmente
    C-->>S: UnlockedVault o error genérico
    S-->>UI: Sesión abierta
    U->>UI: Crea o modifica una entrada
    UI->>R: VaultItem en memoria
    R->>C: Cifra con AAD versionada
    C-->>R: Ciphertext
    R-->>UI: Estado actualizado
```

## Sincronización local-first

```mermaid
sequenceDiagram
    participant R as Repositorio
    participant L as Room
    participant F as Firestore
    R->>L: Guarda ciphertext y marca dirty
    R->>F: Sube ciphertext + metadatos
    F-->>R: Confirmación condicional por revisión
    R->>L: Limpia dirty o conserva conflicto
    F-->>R: Cambios remotos cifrados
    R->>L: Persiste ciphertext o staging de conflicto
```

## Respaldo y restauración

```mermaid
flowchart LR
    L[Room: ciphertext] --> E[Exportación con límites]
    M[Metadatos y envoltorios] --> E
    E --> A[Manifiesto autenticado por AEAD]
    A --> B[Archivo .bwvault]
    B --> V[Parser estricto y límites]
    V --> U[Desenvolver VDEK con contraseña o frase]
    U --> R[Transacción Room]
```
