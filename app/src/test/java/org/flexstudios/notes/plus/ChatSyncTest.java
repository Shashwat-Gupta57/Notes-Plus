package org.flexstudios.notes.plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Chat addressing: both sides must agree on a conversation's key, and IDs are strict. */
public class ChatSyncTest {

    @Test
    public void bothSidesComputeTheSameConversation() {
        String a = ChatSync.chatId("uidAlice", "AB12", "uidBob", "CD34");
        String b = ChatSync.chatId("uidBob", "CD34", "uidAlice", "AB12");
        assertEquals(a, b);
        assertEquals("uidAlice_AB12_uidBob_CD34", a);
    }

    @Test
    public void eachPairOfVaultsIsItsOwnConversation() {
        String main = ChatSync.chatId("alice", "AB12", "bob", "CD34");
        String other = ChatSync.chatId("alice", "ZZ99", "bob", "CD34");
        String theirOther = ChatSync.chatId("alice", "AB12", "bob", "QQ77");
        assertNotEquals(main, other);
        assertNotEquals(main, theirOther);
    }

    @Test
    public void conversationKeysContainEachEndpoint() {
        // The database rules check exactly this when the conversation is created.
        String id = ChatSync.chatId("alice", "AB12", "bob", "CD34");
        assertTrue(id.contains("alice_AB12"));
        assertTrue(id.contains("bob_CD34"));
    }

    @Test
    public void myAddressIsReadFromTheConversationKey() {
        String id = ChatSync.chatId("alice", "AB12", "bob", "CD34");
        assertEquals("AB12", ChatVaults.addressOf(id, "alice"));
        assertEquals("CD34", ChatVaults.addressOf(id, "bob"));
        assertNull(ChatVaults.addressOf(id, "mallory"));
        assertNull(ChatVaults.addressOf("garbage", "alice"));
    }

    @Test
    public void lockHashesDependOnSaltTypeAndValue() throws Exception {
        String salt = "00112233445566778899aabbccddeeff";
        String a = ChatVaults.derive(salt, "PIN", "1234");
        assertEquals(a, ChatVaults.derive(salt, "PIN", "1234"));
        assertEquals(64, a.length());
        assertNotEquals(a, ChatVaults.derive(salt, "PIN", "1235"));
        assertNotEquals(a, ChatVaults.derive(salt, "PASSWORD", "1234"));
        assertNotEquals(a, ChatVaults.derive("ffeeddccbbaa99887766554433221100", "PIN", "1234"));
    }

    @Test
    public void mediaShowsAsWhatItIsInTheList() {
        String photo = "{\"key\":\"k\",\"mime\":\"image/jpeg\",\"size\":1}";
        String video = "{\"key\":\"k\",\"mime\":\"video/mp4\",\"size\":1}";
        assertEquals("Photo", ChatSync.preview("", photo));
        assertEquals("Photo · look", ChatSync.preview("look", photo));
        assertEquals("Video", ChatSync.preview(null, video));
        assertEquals("hi", ChatSync.preview("hi", null));
    }

    @Test
    public void vaultIdsAreNormalisedOrRefused() {
        assertEquals("", ChatSync.normaliseCode(""));
        assertEquals("", ChatSync.normaliseCode("   "));
        assertEquals("", ChatSync.normaliseCode(null));
        assertEquals("AB12", ChatSync.normaliseCode(" ab12 "));
        assertNull(ChatSync.normaliseCode("AB1"));
        assertNull(ChatSync.normaliseCode("AB123"));
        assertNull(ChatSync.normaliseCode("AB-2"));
    }
}
