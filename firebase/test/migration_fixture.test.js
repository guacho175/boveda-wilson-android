'use strict';

// G-58 — fixture de migración: mientras las listas productivas de campos opcionales están
// vacías (docs/architecture.md §6), esta prueba demuestra el mecanismo con un campo ficticio
// 'migrationFixture' antes de que exista un campo opcional real. Usa dos variantes de reglas
// que NUNCA se despliegan (viven solo en test/fixtures/).

const { test, describe, before, after } = require('node:test');
const { doc, setDoc } = require('firebase/firestore');
const {
  FIXTURE_ALICE_UID,
  makeTestEnv,
  validVaultDoc,
  assertSucceeds,
  assertFails,
} = require('./support/helpers');

const VAULT_PATH = `users/${FIXTURE_ALICE_UID}/vaults/vault-1`;

describe('migrationFixture opcional (antes de promover)', () => {
  let testEnv;

  before(async () => {
    testEnv = await makeTestEnv({
      projectId: 'demo-boveda-wilson-public-mig-opt',
      rulesPath: 'test/fixtures/firestore.migration-optional.rules',
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  test('documento sin el campo opcional: aceptado', async () => {
    const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
    await assertSucceeds(setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc()));
  });

  test('documento con el campo opcional presente: aceptado', async () => {
    const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
    await assertSucceeds(
      setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ migrationFixture: 'valor' }))
    );
  });

  test('documento con un campo ajeno no listado: denegado', async () => {
    const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
    await assertFails(
      setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ otroDistinto: 'no' }))
    );
  });
});

describe('migrationFixture promovido a obligatorio', () => {
  let testEnv;

  before(async () => {
    testEnv = await makeTestEnv({
      projectId: 'demo-boveda-wilson-public-mig-req',
      rulesPath: 'test/fixtures/firestore.migration-required.rules',
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  test('documento sin el campo, ya obligatorio: denegado', async () => {
    const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
    await assertFails(setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc()));
  });

  test('documento con el campo presente: aceptado', async () => {
    const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
    await assertSucceeds(
      setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc({ migrationFixture: 'valor' }))
    );
  });

  test('documento con un campo ajeno no listado: denegado', async () => {
    const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
    await assertFails(
      setDoc(
        doc(alice.firestore(), VAULT_PATH),
        validVaultDoc({ migrationFixture: 'valor', otroDistinto: 'no' })
      )
    );
  });
});
