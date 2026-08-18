'use strict';

const { test, before, after, beforeEach } = require('node:test');
const { collection, doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc } = require('firebase/firestore');
const {
  FIXTURE_ALICE_UID,
  FIXTURE_BOB_UID,
  makeTestEnv,
  validVaultDoc,
  validItemDoc,
  bytesOf,
  assertSucceeds,
  assertFails,
} = require('./support/helpers');

const PROJECT_ID = 'demo-boveda-wilson-public-deletion';
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
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(
      doc(db, VAULT_PATH),
      validVaultDoc({ createdAt: 1000, updatedAt: 1000, metaRevision: 1 })
    );
    await setDoc(
      doc(db, ITEM_PATH),
      validItemDoc({ createdAt: 1000, updatedAt: 1000, revision: 1 })
    );
  });
});

function validDeletionMarker(overrides = {}) {
  return { schemaVersion: 1, deletedAt: Date.now(), ...overrides };
}

test('deletion marker: only the owner can create the closed schema', async () => {
  const anon = testEnv.unauthenticatedContext();
  const bob = testEnv.authenticatedContext(FIXTURE_BOB_UID);
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);

  await assertFails(setDoc(doc(anon.firestore(), DELETION_PATH), validDeletionMarker()));
  await assertFails(setDoc(doc(bob.firestore(), DELETION_PATH), validDeletionMarker()));
  await assertFails(
    setDoc(doc(alice.firestore(), DELETION_PATH), validDeletionMarker({ reason: 'plaintext' }))
  );
  await assertSucceeds(setDoc(doc(alice.firestore(), DELETION_PATH), validDeletionMarker()));
  await assertSucceeds(getDoc(doc(alice.firestore(), DELETION_PATH)));
  await assertFails(getDoc(doc(bob.firestore(), DELETION_PATH)));
});

test('deletion marker is immutable and cannot be removed', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(setDoc(doc(alice.firestore(), DELETION_PATH), validDeletionMarker()));
  await assertFails(updateDoc(doc(alice.firestore(), DELETION_PATH), { deletedAt: Date.now() + 1 }));
  await assertFails(deleteDoc(doc(alice.firestore(), DELETION_PATH)));
});

test('deletion markers can only be listed by their owner', async () => {
  const anon = testEnv.unauthenticatedContext();
  const bob = testEnv.authenticatedContext(FIXTURE_BOB_UID);
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(setDoc(doc(alice.firestore(), DELETION_PATH), validDeletionMarker()));

  const markersPath = `users/${FIXTURE_ALICE_UID}/deletedVaults`;
  await assertSucceeds(getDocs(collection(alice.firestore(), markersPath)));
  await assertFails(getDocs(collection(bob.firestore(), markersPath)));
  await assertFails(getDocs(collection(anon.firestore(), markersPath)));
});

test('deletion marker blocks recreation and ordinary writes', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(setDoc(doc(alice.firestore(), DELETION_PATH), validDeletionMarker()));

  await assertFails(
    updateDoc(
      doc(alice.firestore(), VAULT_PATH),
      validVaultDoc({ createdAt: 1000, updatedAt: 2000, metaRevision: 2 })
    )
  );
  await assertFails(
    setDoc(doc(alice.firestore(), `${VAULT_PATH}/items/item-2`), validItemDoc())
  );
  await assertFails(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({ createdAt: 1000, updatedAt: 2000, revision: 2 })
    )
  );
  await assertFails(setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc()));
});

test('deletion marker permits tombstoning and physical deletion only', async () => {
  const alice = testEnv.authenticatedContext(FIXTURE_ALICE_UID);
  await assertSucceeds(setDoc(doc(alice.firestore(), DELETION_PATH), validDeletionMarker()));
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), `${VAULT_PATH}/items/live-item`),
      validItemDoc({ createdAt: 1000, updatedAt: 1000, revision: 1 })
    );
  });

  await assertSucceeds(
    updateDoc(
      doc(alice.firestore(), ITEM_PATH),
      validItemDoc({
        ciphertext: bytesOf(0, 0),
        tombstone: true,
        createdAt: 1000,
        updatedAt: 2000,
        revision: 2,
      })
    )
  );
  await assertSucceeds(deleteDoc(doc(alice.firestore(), ITEM_PATH)));
  await assertSucceeds(deleteDoc(doc(alice.firestore(), `${VAULT_PATH}/items/live-item`)));
  await assertSucceeds(deleteDoc(doc(alice.firestore(), VAULT_PATH)));

  await assertFails(setDoc(doc(alice.firestore(), VAULT_PATH), validVaultDoc()));
  await assertFails(
    setDoc(doc(alice.firestore(), `${VAULT_PATH}/items/item-2`), validItemDoc())
  );
});
