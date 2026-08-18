'use strict';

const { test, before, after, beforeEach } = require('node:test');
const { doc, setDoc, updateDoc, deleteDoc } = require('firebase/firestore');
const {
  FIXTURE_ALICE_UID,
  makeTestEnv,
  validVaultDoc,
  validItemDoc,
  bytesOf,
  assertSucceeds,
  assertFails,
} = require('./support/helpers');

const PROJECT_ID = 'demo-boveda-wilson-public-items';
const VAULT_PATH = `users/${FIXTURE_ALICE_UID}/vaults/vault-1`;
const ITEM_PATH = `${VAULT_PATH}/items/item-1`;
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
  // El documento de bóveda no es objeto de estas pruebas, pero se siembra para reflejar la
  // jerarquía real; las reglas de item no dependen de su contenido.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), VAULT_PATH), validVaultDoc());
  });
});

async function seedValidItem(overrides = {}) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), ITEM_PATH), validItemDoc(overrides));
  });
}

test('G-28/G-57 estructura válida y revision > 1 aceptadas en create', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(setDoc(doc(alice.firestore(), ITEM_PATH), validItemDoc({ revision: 7 })));
});

test('revision = 0 en create: denegado', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(setDoc(doc(alice.firestore(), ITEM_PATH), validItemDoc({ revision: 0 })));
});

test('G-19 no autenticado: denegado', async () => {
  const anon = testEnv.unauthenticatedContext();
  await assertFails(setDoc(doc(anon.firestore(), ITEM_PATH), validItemDoc()));
});

test('G-22 campo no permitido: denegado', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(
    setDoc(doc(alice.firestore(), ITEM_PATH), validItemDoc({ title: 'texto plano, prohibido' }))
  );
});

test('G-23 tipos incorrectos: denegados', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(
    setDoc(doc(alice.firestore(), ITEM_PATH), validItemDoc({ tombstone: 'false' }))
  );
  await assertFails(
    setDoc(doc(alice.firestore(), ITEM_PATH), validItemDoc({ revision: '1' }))
  );
});

test('G-24 tamaño de ciphertext: límite exacto aceptado, +1 denegado', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(
    setDoc(doc(alice.firestore(), ITEM_PATH), validItemDoc({ ciphertext: bytesOf(262144, 9) }))
  );
  await assertFails(
    setDoc(doc(alice.firestore(), ITEM_PATH), validItemDoc({ ciphertext: bytesOf(262145, 9) }))
  );
});

test('G-56 tombstone activo exige ciphertext vacío y viceversa', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(
    setDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ tombstone: true, ciphertext: bytesOf(0, 0) })
    )
  );
  await assertFails(
    setDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ tombstone: true, ciphertext: bytesOf(1, 9) })
    )
  );
  await assertFails(
    setDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ tombstone: false, ciphertext: bytesOf(0, 0) })
    )
  );
});

test('G-59 updatedAt: tolerancia de reloj de 5 minutos exacta', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const now = Date.now();
  await assertSucceeds(
    setDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ createdAt: now, updatedAt: now + 300000 })
    )
  );
  await assertFails(
    setDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ createdAt: now, updatedAt: now + 300001 })
    )
  );
});

test('G-25 createdAt inmutable en update', async () => {
  await seedValidItem({ createdAt: 1000, updatedAt: 1000, revision: 1 });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ createdAt: 2000, updatedAt: 3000, revision: 2 })
    )
  );
});

test('G-26 revision no retrocede ni se repite en update', async () => {
  await seedValidItem({ createdAt: 1000, updatedAt: 1000, revision: 5 });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);

  await assertFails(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ createdAt: 1000, updatedAt: 2000, revision: 5 })
    )
  );
  await assertFails(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ createdAt: 1000, updatedAt: 2000, revision: 4 })
    )
  );
  await assertSucceeds(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ createdAt: 1000, updatedAt: 2000, revision: 6 })
    )
  );
});

test('G-58 cryptoVersion y schemaVersion no retroceden en update', async () => {
  await seedValidItem({
    createdAt: 1000,
    updatedAt: 1000,
    revision: 1,
    cryptoVersion: 2,
    schemaVersion: 2,
  });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);

  await assertFails(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({
        createdAt: 1000,
        updatedAt: 2000,
        revision: 2,
        cryptoVersion: 1, // retrocede: debe denegarse
        schemaVersion: 2,
      })
    )
  );
  await assertFails(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({
        createdAt: 1000,
        updatedAt: 2000,
        revision: 2,
        cryptoVersion: 2,
        schemaVersion: 1, // retrocede: debe denegarse
      })
    )
  );
  await assertSucceeds(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({
        createdAt: 1000,
        updatedAt: 2000,
        revision: 2,
        cryptoVersion: 2,
        schemaVersion: 2,
      })
    )
  );
});

test('G-27 borrado físico: solo si el ítem ya es tombstone', async () => {
  await seedValidItem({ tombstone: false, ciphertext: bytesOf(8, 9) });
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(deleteDoc(doc(alice.firestore(), ITEM_PATH)));

  await seedValidItem({ tombstone: true, ciphertext: bytesOf(0, 0) });
  await assertFails(deleteDoc(doc(alice.firestore(), ITEM_PATH)));
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), DELETION_PATH), {
      schemaVersion: 1,
      deletedAt: Date.now(),
    });
  });
  await assertSucceeds(deleteDoc(doc(alice.firestore(), ITEM_PATH)));
});
