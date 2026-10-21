package org.flexstudios.notes.plus;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.room.Delete;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Local storage of the chat space: its vaults, conversations and messages.
 *
 * Deliberately separate tables from the gallery's {@code vaults}, so no gallery query can ever
 * return a chat vault (or the reverse) by mistake. Messages live here in full; the database
 * online only keeps the latest 100 per conversation.
 */
public final class ChatStore {

    private ChatStore() {}

    /** Message states, in the order they advance. Failed is "not sent". */
    static final int FAILED = -1, WAITING = 0, SENT = 1, RECEIVED = 2, SEEN = 3;

    /**
     * One chat vault: its own lock, and the 4-character ID people add to its address.
     *
     * Synced to every phone of the account ({@code chatVaults/{uid}/{code}}): newest
     * {@code updatedAt} wins, {@code dirty} means not pushed yet, {@code deleted} is a
     * tombstone so a deletion reaches phones that still have it. {@code lockValue} is never the
     * lock itself but {@link ChatSync#hashLock}'s salted hash.
     */
    @Entity(tableName = "chat_vaults", indices = {@Index(value = "code", unique = true)})
    public static class Vault {
        @PrimaryKey(autoGenerate = true)
        public int id;
        public String name;
        public String lockType;
        public String lockValue;
        @NonNull
        public String code = "";
        public boolean isMain;
        public long createdAt;
        public long updatedAt;
        public boolean dirty = true;
        public boolean deleted;
        /** This vault's own profile photo (ChatMedia.Meta JSON), shown to people it talks to. */
        public String photo;
        /** Background of this vault's chat list on this phone: {"kind":"color"|"image",...}. */
        public String background;
        /** On a retired ID: the ID it became, so messages to the old one still arrive. */
        public String renamedTo;
    }

    /** One conversation: this vault with one person's vault. */
    @Entity(tableName = "chat_threads",
            indices = {@Index(value = {"vaultId", "chatId"}, unique = true), @Index({"vaultId", "lastAt"})})
    public static class Conversation {
        @PrimaryKey(autoGenerate = true)
        public int id;
        public int vaultId;
        /** The conversation's key online; the same on both sides. */
        @NonNull
        public String chatId = "";
        public String peerUid;
        public String peerName;
        public String peerVault;
        public String lastText;
        public long lastAt;
        public int unread;
        /** Newest message key fetched so far. */
        public String cursor;
        /** The conversation exists online (its members are written). */
        public boolean established;
        /** Moved to another vault here; the move isn't recorded online yet. */
        public boolean moved;
        /** My private name for them, synced to my other phones. Null: their username. */
        public String nickname;
        /** The shared background: {"kind":"color","value":"#RRGGBB"} or {"kind":"image","media":{...}}. */
        public String theme;
        /** My vault's ID as the other person should see it, when it changed since this began. */
        public String shownAs;
        /** Their vault's current ID, if it changed since this began (else peerVault). */
        public String peerShown;
        /** Their photo: {"url":...} (Google) or ChatMedia.Meta JSON (custom). */
        public String peerPhoto;
    }

    @Entity(tableName = "chat_messages",
            indices = {@Index(value = "msgKey", unique = true), @Index({"threadId", "msgKey"})})
    public static class Message {
        @PrimaryKey(autoGenerate = true)
        public int id;
        public int threadId;
        /** Push key: made on this phone before sending, so a retry can never duplicate. */
        @NonNull
        public String msgKey = "";
        public boolean mine;
        public String text;
        /** When it was written (mine) or sent (theirs). */
        public long at;
        public int status;
        /** A photo or video: {@link ChatMedia.Meta} as JSON. Null for text. */
        public String media;
    }

    @androidx.room.Dao
    public interface Dao {
        // ------------------------------------------------------------- vaults

        @Insert
        long insertVault(Vault vault);

        @Update
        void updateVault(Vault vault);

        @Delete
        void deleteVault(Vault vault);

        @Query("SELECT * FROM chat_vaults WHERE deleted = 0 ORDER BY isMain DESC, createdAt")
        List<Vault> vaults();

        @Query("SELECT * FROM chat_vaults WHERE deleted = 0 ORDER BY isMain DESC, createdAt")
        LiveData<List<Vault>> vaultsLive();

        @Query("SELECT * FROM chat_vaults WHERE id = :id AND deleted = 0")
        Vault vault(int id);

        @Query("SELECT * FROM chat_vaults WHERE code = :code AND deleted = 0")
        Vault vaultByCode(String code);

        /** Tombstones included: an ID once used stays taken. */
        @Query("SELECT * FROM chat_vaults WHERE code = :code")
        Vault vaultByCodeAny(String code);

        @Query("SELECT * FROM chat_vaults WHERE dirty = 1")
        List<Vault> dirtyVaults();

        @Query("SELECT * FROM chat_vaults WHERE isMain = 1 AND deleted = 0 ORDER BY createdAt LIMIT 1")
        Vault mainVault();

        // ------------------------------------------------------ conversations

        @Insert
        long insertConversation(Conversation conversation);

        @Update
        void updateConversation(Conversation conversation);

        @Query("SELECT * FROM chat_threads WHERE vaultId = :vaultId ORDER BY lastAt DESC")
        LiveData<List<Conversation>> conversations(int vaultId);

        @Query("SELECT * FROM chat_threads WHERE vaultId = :vaultId AND chatId = :chatId")
        Conversation conversation(int vaultId, String chatId);

        @Query("SELECT * FROM chat_threads WHERE id = :id")
        Conversation conversation(int id);

        /** A conversation lives in exactly one vault on a phone, wherever it was moved. */
        @Query("SELECT * FROM chat_threads WHERE chatId = :chatId LIMIT 1")
        Conversation conversationByChatId(String chatId);

        @Query("SELECT * FROM chat_threads WHERE vaultId = :vaultId")
        List<Conversation> conversationsIn(int vaultId);

        @Query("SELECT * FROM chat_threads WHERE moved = 1")
        List<Conversation> movedConversations();

        @Query("SELECT * FROM chat_threads")
        List<Conversation> allConversations();

        @Query("UPDATE chat_threads SET unread = 0 WHERE id = :id")
        void markRead(int id);

        @Query("DELETE FROM chat_threads WHERE id = :id")
        void deleteConversation(int id);

        @Query("DELETE FROM chat_threads WHERE vaultId = :vaultId")
        void deleteConversationsOf(int vaultId);

        // ----------------------------------------------------------- messages

        @Insert(onConflict = OnConflictStrategy.IGNORE)
        long insertMessage(Message message);

        @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY msgKey")
        LiveData<List<Message>> messages(int threadId);

        @Query("SELECT * FROM chat_messages WHERE msgKey = :key")
        Message message(String key);

        @Query("SELECT * FROM chat_messages WHERE mine = 1 AND status = 0 ORDER BY msgKey")
        List<Message> waiting();

        /** Moves a message forward only: a late "sent" never undoes "seen". */
        @Query("UPDATE chat_messages SET status = :status WHERE msgKey = :key AND status < :status")
        void advance(String key, int status);

        @Query("UPDATE chat_messages SET status = :status WHERE msgKey = :key")
        void setStatus(String key, int status);

        @Query("UPDATE chat_messages SET media = :media WHERE msgKey = :key")
        void setMedia(String key, String media);

        /** The oldest message of mine whose status can still change. */
        @Query("SELECT MIN(msgKey) FROM chat_messages WHERE threadId = :threadId AND mine = 1 AND status IN (1, 2)")
        String oldestOpen(int threadId);

        @Query("SELECT msgKey FROM chat_messages WHERE threadId = :threadId AND mine = 0 AND status < 3")
        List<String> unseen(int threadId);

        @Query("UPDATE chat_messages SET status = 3 WHERE threadId = :threadId AND mine = 0")
        void markSeen(int threadId);

        @Query("DELETE FROM chat_messages WHERE threadId = :threadId")
        void deleteMessagesOf(int threadId);

        @Query("DELETE FROM chat_messages WHERE threadId IN (SELECT id FROM chat_threads WHERE vaultId = :vaultId)")
        void deleteMessagesOfVault(int vaultId);
    }
}
