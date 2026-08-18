'use strict';

const { test, before, after, beforeEach } = require('node:test');
const {
  doc,
  setDoc,
  getDoc,
  updateDoc,
  deleteDoc,
  collection,
  collectionGroup,
  getDocs,
  query,
} = require('firebase/firestore');
const {
  FIXTURE_ALICE_UID,
  FIXTURE_BOB_UID,
  makeTestEnv,
  validVaultDoc,
  validItemDoc,
  assertSucceeds,
  assertFails,
} = require('./support/helpers');

const PROJECT_ID = 'demo-boveda-wilson-public-isolation';
const ALICE_VAULT_PATH = `users/${FIXTURE_ALICE_UID}/vaults/vault-1`;
const ALICE_ITEM_PATH = `${ALICE_VAULT_PATH}/items/item-1`;

let testEnv;

before(async () => {
  testEnv = await makeTestEnv({ projectId: PROJECT_ID, rulesPath: 'firestore.rules' });
});

after(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, ALICE_VAULT_PATH), validVaultDoc());
    await setDoc(doc(db, ALICE_ITEM_PATH), validItemDoc());
  });
});

test('G-20 B no puede leer la bóveda ni los ítems de A', async () => {
  const bob = testEnv.authenticatedContext(FIXTURE_BOB_UID);
  await assertFails(getDoc(doc(bob.firestore(), ALICE_VAULT_PATH)));
  await assertFails(getDoc(doc(bob.firestore(), ALICE_ITEM_PATH)));
});

test('G-21 B no puede escribir la bóveda ni los ítems de A', async () => {
  const bob = testEnv.authenticatedContext(FIXTURE_BOB_UID);
  await assertFails(
    updateDoc(doc(bob.firestore(), ALICE_VAULT_PATH), { metaRevision: 999 })
  );
  await assertFails(deleteDoc(doc(bob.firestore(), ALICE_VAULT_PATH)));
  await assertFails(
    updateDoc(doc(bob.firestore(), ALICE_ITEM_PATH), validItemDoc({ revision: 999 }))
  );
  await assertFails(setDoc(doc(bob.firestore(), ALICE_ITEM_PATH), validItemDoc()));
});

test('A no puede leer ni escribir bajo la ruta de B (aunque no exista nada ahí)', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const bobVaultPath = `users/${FIXTURE_BOB_UID}/vaults/vault-1`;
  await assertFails(getDoc(doc(alice.firestore(), bobVaultPath)));
  await assertFails(setDoc(doc(alice.firestore(), bobVaultPath), validVaultDoc()));
});

test('G-60 collectionGroup("items") entre usuarios: denegado por defecto', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertFails(getDocs(query(collectionGroup(alice.firestore(), 'items'))));
});

test('G-60 rutas hermanas fuera del esquema: denegadas', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const siblingDoc = doc(alice.firestore(), `users/${FIXTURE_ALICE_UID}/notes/note-1`);
  await assertFails(setDoc(siblingDoc, { anything: 'no' }));
  await assertFails(getDoc(siblingDoc));

  const rootSibling = doc(alice.firestore(), 'vaults/vault-1');
  await assertFails(getDoc(rootSibling));
});

test('list de la colección vaults: solo el propietario', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const bob = testEnv.authenticatedContext(FIXTURE_BOB_UID);
  await assertSucceeds(getDocs(collection(alice.firestore(), `users/${FIXTURE_ALICE_UID}/vaults`)));
  await assertFails(getDocs(collection(bob.firestore(), `users/${FIXTURE_ALICE_UID}/vaults`)));
});

test('G-20 (list) B no puede listar la subcolección items de A', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  const bob = testEnv.authenticatedContext(FIXTURE_BOB_UID);
  await assertSucceeds(getDocs(collection(alice.firestore(), `${ALICE_VAULT_PATH}/items`)));
  await assertFails(getDocs(collection(bob.firestore(), `${ALICE_VAULT_PATH}/items`)));
});
