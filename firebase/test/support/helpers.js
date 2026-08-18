'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const { Bytes } = require('firebase/firestore');

const FIREBASE_ROOT = path.join(__dirname, '..', '..');

// FIXTURE_* : identificadores ficticios de prueba, no corresponden a cuentas reales.
const FIXTURE_ALICE_UID = 'fixture-alice-uid';
const FIXTURE_BOB_UID = 'fixture-bob-uid';

function readRules(relativePath) {
  return fs.readFileSync(path.join(FIREBASE_ROOT, relativePath), 'utf8');
}

// Cada archivo de prueba (vault/items/isolation/migration_fixture) pasa su propio
// `projectId` a makeTestEnv. Todos comparten el mismo Firestore Emulator del proceso
// `firebase emulators:exec`, pero el emulador aísla los datos por projectId; usar uno
// distinto por archivo evita que un `clearFirestore()` o una escritura de un archivo
// pisen los documentos de otro cuando ambos usan la misma jerarquía de rutas
// (`users/{uid}/vaults/{vaultId}`). `singleProjectMode` en firebase.json solo afecta
// al proyecto por defecto de la CLI; no impide usar projectId distintos vía
// `initializeTestEnvironment`.
async function makeTestEnv({ projectId, rulesPath }) {
  return initializeTestEnvironment({
    projectId,
    firestore: {
      rules: readRules(rulesPath),
      host: '127.0.0.1',
      port: 8080,
    },
  });
}

function bytesOf(length, fill) {
  return Bytes.fromUint8Array(new Uint8Array(length).fill(fill));
}

function validVaultDoc(overrides = {}) {
  const now = Date.now();
  const base = {
    schemaVersion: 1,
    cryptoVersion: 1,
    kdfName: 'argon2id',
    kdfMemoryKib: 65536,
    kdfIterations: 3,
    kdfParallelism: 4,
    kdfOutputLen: 32,
    passwordSalt: bytesOf(16, 1),
    passwordWrappedVdek: bytesOf(64, 2),
    recoverySalt: bytesOf(32, 3),
    recoveryWrappedVdek: bytesOf(64, 4),
    passwordWrapEpoch: 1,
    recoveryWrapEpoch: 1,
    createdAt: now,
    updatedAt: now,
    metaRevision: 1,
  };
  return { ...base, ...overrides };
}

function validItemDoc(overrides = {}) {
  const now = Date.now();
  const base = {
    ciphertext: bytesOf(32, 9),
    cryptoVersion: 1,
    schemaVersion: 1,
    revision: 1,
    tombstone: false,
    createdAt: now,
    updatedAt: now,
  };
  return { ...base, ...overrides };
}

module.exports = {
  FIXTURE_ALICE_UID,
  FIXTURE_BOB_UID,
  makeTestEnv,
  bytesOf,
  validVaultDoc,
  validItemDoc,
  assertSucceeds,
  assertFails,
};
