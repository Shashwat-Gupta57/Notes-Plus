package org.flexstudios.notes.plus;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a new restore point follows the last one: deletions stick, revivals undo them, locks follow
 * the toggle. The phone's SnapshotSettleTest and the web's vault-sync tests run the same cases.
 */
class SnapshotSettleTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static Snapshot snap(String json) {
        return Snapshot.fromJson(new JSONObject(json));
    }

    private static List<String> names(Snapshot s) {
        return s.media.stream().map(m -> m.fileName).sorted().toList();
    }

    private static final String PREVIOUS = "{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true,\"lockType\":\"PIN\",\"lockValue\":\"1111\",\"lockAt\":5},"
            + "{\"uuid\":\"v2\",\"lockType\":\"PIN\",\"lockValue\":\"2222\",\"lockAt\":5}],"
            + "\"albums\":[{\"uuid\":\"a1\",\"vaultUuid\":\"m\"}],"
            + "\"media\":[{\"fileName\":\"IMG_A\",\"vaultUuid\":\"m\"},{\"fileName\":\"IMG_B\",\"vaultUuid\":\"m\"}],"
            + "\"removed\":{\"IMG_OLD\":" + (NOW - 61 * DAY) + ",\"IMG_C\":" + (NOW - DAY) + "}}";

    @Test
    void anItemDeletedHereStaysDeleted() {
        Snapshot mine = snap("{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true,\"lockType\":\"PIN\",\"lockValue\":\"1111\",\"lockAt\":5}],"
                + "\"media\":[{\"fileName\":\"IMG_A\",\"vaultUuid\":\"m\"}]}");
        mine.settle(snap(PREVIOUS), Set.of("IMG_A", "IMG_B"), Map.of("IMG_B", NOW), Set.of(), false, NOW);
        assertEquals(List.of("IMG_A"), names(mine));
        assertTrue(mine.removed.containsKey("IMG_B"));
    }

    @Test
    void anItemDeletedElsewhereIsntSentBack() {
        Snapshot mine = snap("{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true}],"
                + "\"media\":[{\"fileName\":\"IMG_A\",\"vaultUuid\":\"m\"},{\"fileName\":\"IMG_C\",\"vaultUuid\":\"m\"}]}");
        mine.settle(snap(PREVIOUS), Set.of("IMG_A", "IMG_B", "IMG_C"), Map.of(), Set.of(), false, NOW);
        assertEquals(List.of("IMG_A", "IMG_B"), names(mine));
    }

    @Test
    void aRevivedItemComesBack() {
        Snapshot mine = snap("{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true}],"
                + "\"media\":[{\"fileName\":\"IMG_C\",\"vaultUuid\":\"m\"}]}");
        mine.settle(snap(PREVIOUS), Set.of("IMG_A", "IMG_B", "IMG_C"), Map.of(), Set.of("IMG_C"), false, NOW);
        assertEquals(List.of("IMG_A", "IMG_B", "IMG_C"), names(mine));
        assertFalse(mine.removed.containsKey("IMG_C"));
    }

    @Test
    void oldDeletionsAreForgotten() {
        Snapshot mine = snap("{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true}]}");
        mine.settle(snap(PREVIOUS), Set.of("IMG_A", "IMG_B"), Map.of(), Set.of(), false, NOW);
        assertFalse(mine.removed.containsKey("IMG_OLD"));
        assertTrue(mine.removed.containsKey("IMG_C"));
    }

    @Test
    void deletedAlbumsAndVaultsStayDeleted() {
        Snapshot mine = snap("{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true}]}");
        mine.settle(snap(PREVIOUS), Set.of(), Map.of("album:a1", NOW, "vault:v2", NOW), Set.of(), false, NOW);
        assertTrue(mine.albums.isEmpty());
        assertEquals(1, mine.vaults.size());
        assertEquals("m", mine.vaults.get(0).uuid);
    }

    @Test
    void withLockSyncOffTheBackupsLockStays() {
        Snapshot mine = snap("{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true,\"lockType\":\"PIN\",\"lockValue\":\"9999\",\"lockAt\":9}]}");
        mine.settle(snap(PREVIOUS), Set.of(), Map.of(), Set.of(), false, NOW);
        assertEquals("1111", mine.vaults.get(0).lockValue);
        assertEquals(5, mine.vaults.get(0).lockAt);
    }

    @Test
    void withLockSyncOnTheNewestLockWins() {
        Snapshot newer = snap("{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true,\"lockType\":\"PIN\",\"lockValue\":\"9999\",\"lockAt\":9}]}");
        newer.settle(snap(PREVIOUS), Set.of(), Map.of(), Set.of(), true, NOW);
        assertEquals("9999", newer.vaults.get(0).lockValue);
        Snapshot older = snap("{\"vaults\":[{\"uuid\":\"m\",\"isMain\":true,\"lockType\":\"PATTERN\",\"lockValue\":\"0125\",\"lockAt\":3}]}");
        older.settle(snap(PREVIOUS), Set.of(), Map.of(), Set.of(), true, NOW);
        assertEquals("1111", older.vaults.get(0).lockValue);
        assertEquals("PIN", older.vaults.get(0).lockType);
    }

    @Test
    void removedAndLockAtSurviveTheFormat() {
        Snapshot s = snap(PREVIOUS);
        Snapshot back = Snapshot.fromJson(new JSONObject(s.toJson().toString()));
        assertEquals(s.removed, back.removed);
        assertEquals(5, back.vaults.get(0).lockAt);
    }
}
