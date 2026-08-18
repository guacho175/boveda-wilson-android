'use strict';

const { test, before, after, beforeEach } = require('node:test');
const { doc, getDoc, setDoc, updateDoc, deleteDoc } = require('firebase/firestore');
const {
  FIXTURE_ALICE_UID,
  makeTestEnv,
  validVaultDoc,
  assertSucceeds,
  assertFails,
} = require('./support/helpers');

const PROJECT_ID = 'demo-boveda-wilson-public-vault';
const VAULT_PATH = `users/${FIXTURE_ALICE_UID}/vaults/vault-1`;
const DELETION_PATH = `users/${FIXTURE_ALICE_UID}/deletedVaults/vault-1`;

let testEnv;

before(async () => {
  testEnv = await makeTestEnv({ projectId: PROJECT_ID, rulesPath: 'firestore.rules' });
});

after(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

async function seedValidVault(overrides = {}) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), VAULT_PATH), validVaultDoc(overrides));
  });
}

test('G-28 estructura válida se acepta en create', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc()));
});

test('G-19 no autenticado: denegado en get y en create', async () => {
  const anon = testEnv.unauthenticatedContext();
  await assertFails(getDoc(doc(anon.firestore(), VAULT_PATH)));
  await assertFails(setDoc(doc(anon.firestore(), VAULT_PATH), validVaultDoc()));
});

test('G-22 campo no permitido: denegado', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ unexpectedField: 'no' }))
  );
});

test('G-23 tipos incorrectos: denegados', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ kdfMemoryKib: '65536' }))
  );
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ kdfName: 'argon2i' }))
  );
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ metaRevision: '1' }))
  );
});

test('G-24/G-59 tamaños exactos de salts y wraps', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const { bytesOf } = require('./support/helpers');

  // passwordSalt debe ser exactamente 16 bytes.
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ passwordSalt: bytesOf(15, 1) }))
  );
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ passwordSalt: bytesOf(17, 1) }))
  );

  // recoverySalt debe ser exactamente 32 bytes.
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ recoverySalt: bytesOf(31, 3) }))
  );
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ recoverySalt: bytesOf(33, 3) }))
  );

  // passwordWrappedVdek: vacío denegado; en el límite exacto (8192) aceptado; +1 denegado.
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ passwordWrappedVdek: bytesOf(0, 2) }))
  );
  await assertSucceeds(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ passwordWrappedVdek: bytesOf(8192, 2) }))
  );
  await assertFails(
    setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ passwordWrappedVdek: bytesOf(8193, 2) }))
  );
});

test('G-59 updatedAt: tolerancia de reloj de 5 minutos exacta', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const now = Date.now();
  await assertSucceeds(
    setDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({ createdAt: now, updatedAt: now + 300000 })
    )
  );
  await assertFails(
    setDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({ createdAt: now, updatedAt: now + 300001 })
    )
  );
});

test('G-25 createdAt inmutable en update', async () => {
  await seedValidVault({ createdAt: 1000, updatedAt: 1000, metaRevision: 1 });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({ createdAt: 2000, updatedAt: 3000, metaRevision: 2 })
    )
  );
});

test('G-26/G-58 metaRevision debe crecer estrictamente en update', async () => {
  await seedValidVault({ createdAt: 1000, updatedAt: 1000, metaRevision: 5 });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);

  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({ createdAt: 1000, updatedAt: 2000, metaRevision: 5 })
    )
  );
  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({ createdAt: 1000, updatedAt: 2000, metaRevision: 4 })
    )
  );
  await assertSucceeds(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({ createdAt: 1000, updatedAt: 2000, metaRevision: 6 })
    )
  );
});

test('G-58 downgrade de parámetros KDF denegado', async () => {
  await seedValidVault({
    createdAt: 1000,
    updatedAt: 1000,
    metaRevision: 1,
    kdfMemoryKib: 65536,
  });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({
        createdAt: 1000,
        updatedAt: 2000,
        metaRevision: 2,
        kdfMemoryKib: 32768, // downgrade
        passwordWrapEpoch: 2,
      })
    )
  );
});

test('G-58 cryptoVersion y schemaVersion no retroceden en update (bóveda)', async () => {
  await seedValidVault({
    createdAt: 1000,
    updatedAt: 1000,
    metaRevision: 1,
    cryptoVersion: 2,
    schemaVersion: 2,
  });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);

  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({
        createdAt: 1000,
        updatedAt: 2000,
        metaRevision: 2,
        cryptoVersion: 1, // retrocede: debe denegarse
        schemaVersion: 2,
      })
    )
  );
  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({
        createdAt: 1000,
        updatedAt: 2000,
        metaRevision: 2,
        cryptoVersion: 2,
        schemaVersion: 1, // retrocede: debe denegarse
      })
    )
  );
});

test('G-58 epochs independientes: cambiar solo un camino no exige tocar el otro', async () => {
  await seedValidVault({
    createdAt: 1000,
    updatedAt: 1000,
    metaRevision: 1,
    passwordWrapEpoch: 1,
    recoveryWrapEpoch: 1,
  });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const { bytesOf } = require('./support/helpers');

  // Cambiar solo el envoltorio de contraseña, con solo su epoch incrementado: aceptado.
  await assertSucceeds(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({
        createdAt: 1000,
        updatedAt: 2000,
        metaRevision: 2,
        passwordWrappedVdek: bytesOf(64, 42),
        passwordWrapEpoch: 2,
        recoveryWrapEpoch: 1,
      })
    )
  );
});

test('G-58 cambiar el envoltorio sin subir su epoch: denegado', async () => {
  await seedValidVault({
    createdAt: 1000,
    updatedAt: 1000,
    metaRevision: 1,
    passwordWrapEpoch: 1,
    recoveryWrapEpoch: 1,
  });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const { bytesOf } = require('./support/helpers');

  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({
        createdAt: 1000,
        updatedAt: 2000,
        metaRevision: 2,
        passwordWrappedVdek: bytesOf(64, 42),
        passwordWrapEpoch: 1, // no incrementado
        recoveryWrapEpoch: 1,
      })
    )
  );
});

test('G-58 subir un epoch sin cambiar su envoltorio: denegado', async () => {
  await seedValidVault({
    createdAt: 1000,
    updatedAt: 1000,
    metaRevision: 1,
    passwordWrapEpoch: 1,
    recoveryWrapEpoch: 1,
  });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);

  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({
        createdAt: 1000,
        updatedAt: 2000,
        metaRevision: 2,
        passwordWrapEpoch: 2, // subido sin cambiar el envoltorio
        recoveryWrapEpoch: 1,
      })
    )
  );
});

test('G-27 borrado físico de la bóveda: solo su propietario', async () => {
  await seedValidVault();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), DELETION_PATH), {
      schemaVersion: 1,
      deletedAt: Date.now(),
    });
  });
  const { FIXTURE_BOB_UID } = require('./support/helpers');
  const bob = testEnv.authenticatedContext(FIXTURE_BOB_UID);
  await assertFails(deleteDoc(doc(bob.firestore(), VAULT_PATH)));

  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(deleteDoc(doc(alice.firestore(), VAULT_PATH)));
});
