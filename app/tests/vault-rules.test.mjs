// The desktop's and the phone's SnapshotSettleTest, case for case: node app/tests/vault-rules.test.mjs
import assert from 'node:assert/strict';
import { normalise, settle } from '../js/vault-rules.js';

const NOW = 1_800_000_000_000;
const DAY = 24 * 60 * 60 * 1000;
const snap = o => normalise(JSON.parse(JSON.stringify(o)));
const names = s => s.media.map(m => m.fileName).sort();

const PREVIOUS = {
  vaults: [{ uuid: 'm', isMain: true, lockType: 'PIN', lockValue: '1111', lockAt: 5 },
    { uuid: 'v2', lockType: 'PIN', lockValue: '2222', lockAt: 5 }],
  albums: [{ uuid: 'a1', vaultUuid: 'm' }],
  media: [{ fileName: 'IMG_A', vaultUuid: 'm' }, { fileName: 'IMG_B', vaultUuid: 'm' }],
  removed: { IMG_OLD: NOW - 61 * DAY, IMG_C: NOW - DAY },
};
const all = new Set(['IMG_A', 'IMG_B', 'IMG_C']);
const cases = {
  anItemDeletedHereStaysDeleted() {
    const mine = snap({ vaults: [{ uuid: 'm', isMain: true, lockType: 'PIN', lockValue: '1111', lockAt: 5 }], media: [{ fileName: 'IMG_A', vaultUuid: 'm' }] });
    settle(mine, snap(PREVIOUS), new Set(['IMG_A', 'IMG_B']), { IMG_B: NOW }, new Set(), false, NOW);
    assert.deepEqual(names(mine), ['IMG_A']);
    assert.ok('IMG_B' in mine.removed);
  },
  anItemDeletedElsewhereIsntSentBack() {
    const mine = snap({ vaults: [{ uuid: 'm', isMain: true }], media: [{ fileName: 'IMG_A', vaultUuid: 'm' }, { fileName: 'IMG_C', vaultUuid: 'm' }] });
    settle(mine, snap(PREVIOUS), all, {}, new Set(), false, NOW);
    assert.deepEqual(names(mine), ['IMG_A', 'IMG_B']);
  },
  aRevivedItemComesBack() {
    const mine = snap({ vaults: [{ uuid: 'm', isMain: true }], media: [{ fileName: 'IMG_C', vaultUuid: 'm' }] });
    settle(mine, snap(PREVIOUS), all, {}, new Set(['IMG_C']), false, NOW);
    assert.deepEqual(names(mine), ['IMG_A', 'IMG_B', 'IMG_C']);
    assert.ok(!('IMG_C' in mine.removed));
  },
  oldDeletionsAreForgotten() {
    const mine = snap({ vaults: [{ uuid: 'm', isMain: true }] });
    settle(mine, snap(PREVIOUS), new Set(['IMG_A', 'IMG_B']), {}, new Set(), false, NOW);
    assert.ok(!('IMG_OLD' in mine.removed));
    assert.ok('IMG_C' in mine.removed);
  },
  deletedAlbumsAndVaultsStayDeleted() {
    const mine = snap({ vaults: [{ uuid: 'm', isMain: true }] });
    settle(mine, snap(PREVIOUS), new Set(), { 'album:a1': NOW, 'vault:v2': NOW }, new Set(), false, NOW);
    assert.equal(mine.albums.length, 0);
    assert.deepEqual(mine.vaults.map(v => v.uuid), ['m']);
  },
  withLockSyncOffTheBackupsLockStays() {
    const mine = snap({ vaults: [{ uuid: 'm', isMain: true, lockType: 'PIN', lockValue: '9999', lockAt: 9 }] });
    settle(mine, snap(PREVIOUS), new Set(), {}, new Set(), false, NOW);
    assert.equal(mine.vaults[0].lockValue, '1111');
    assert.equal(mine.vaults[0].lockAt, 5);
  },
  withLockSyncOnTheNewestLockWins() {
    const newer = snap({ vaults: [{ uuid: 'm', isMain: true, lockType: 'PIN', lockValue: '9999', lockAt: 9 }] });
    settle(newer, snap(PREVIOUS), new Set(), {}, new Set(), true, NOW);
    assert.equal(newer.vaults[0].lockValue, '9999');
    const older = snap({ vaults: [{ uuid: 'm', isMain: true, lockType: 'PATTERN', lockValue: '0125', lockAt: 3 }] });
    settle(older, snap(PREVIOUS), new Set(), {}, new Set(), true, NOW);
    assert.equal(older.vaults[0].lockValue, '1111');
    assert.equal(older.vaults[0].lockType, 'PIN');
  },
};
let failed = 0;
for (const [name, f] of Object.entries(cases)) {
  try { f(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, e.message); }
}
process.exit(failed ? 1 : 0);
