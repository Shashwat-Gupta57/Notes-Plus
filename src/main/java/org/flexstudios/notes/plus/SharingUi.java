package org.flexstudios.notes.plus;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The sharing dialogs, from a card's options or an editor's menu: sending a note to people,
 * a group note's people, and what a received note offers (save a copy, remove).
 */
final class SharingUi {

    private SharingUi() {}

    /** "Send to people": who has it, send it to more, stop sending it to someone. */
    static void sendSheet(int noteId) {
        if (Account.uid() == null) {
            Ui.toast("Sign in to your account to send notes");
            return;
        }
        NoteStore.load(s -> s.byId(noteId), note -> {
            if (note == null) {
                Ui.toast("Write something first, then send it");
                return;
            }
            JSONObject to = NoteSync.json(note.getSentTo());
            List<String> uids = new ArrayList<>(to.keySet());
            uids.sort((a, b) -> to.optString(a).compareToIgnoreCase(to.optString(b)));
            List<String> rows = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (String uid : uids) {
                rows.add("@" + to.optString(uid) + "  ·  click to stop");
                actions.add(() -> confirmStop(note, uid, to.optString(uid)));
            }
            Ui.DialogBuilder d = Ui.dialog().title(uids.isEmpty() ? "Send to people" : "Sent to " + uids.size())
                    .positive("Send to…", () -> askPeople("Send to", "Send", people -> {
                        SentNotes.send(noteId, people);
                        return "Sent. It's in their notes, under Shared with me.";
                    }))
                    .negative("Close", null).width(440);
            if (uids.isEmpty()) {
                d.message("They'll find it under \"Shared with me\" in their notes, with your @username. "
                        + "They can read it but not change it, and they always see your latest version.");
            } else {
                d.items(rows, actions);
            }
            d.show();
        });
    }

    private static void confirmStop(NoteEntity note, String uid, String name) {
        Ui.dialog().title("Stop sending to @" + name + "?")
                .message("It disappears from their notes. They keep any copy they saved themselves.")
                .positive("Stop", () -> NoteStore.load(s -> {
                    try {
                        SentNotes.stop(s.byId(note.getId()), uid);
                        return null;
                    } catch (Exception e) {
                        return "Couldn't change that right now";
                    }
                }, problem -> {
                    if (problem != null) Ui.toast(problem);
                }))
                .negative("Cancel", null).width(420).show();
    }

    /** A group note's people: who's in, invite more, remove someone (owner) or leave. */
    static void peopleSheet(int noteId) {
        if (Account.uid() == null) {
            Ui.toast("Sign in to your account to share notes");
            return;
        }
        NoteStore.load(s -> {
            NoteEntity n = s.byId(noteId);
            return n == null ? null : new Object[]{n, GroupNotes.owner(n)};
        }, r -> {
            if (r == null) {
                Ui.toast("Write something first, then share it");
                return;
            }
            NoteEntity note = (NoteEntity) r[0];
            String owner = (String) r[1], me = Account.uid();
            boolean shared = note.getGroupId() != null, iOwn = me != null && me.equals(owner);
            JSONObject members = NoteSync.json(note.getGroupMembers());
            List<String> uids = new ArrayList<>(members.keySet());
            uids.sort((a, b) -> members.optString(a).compareToIgnoreCase(members.optString(b)));
            List<String> rows = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (String uid : uids) {
                rows.add("@" + members.optString(uid) + (uid.equals(owner) ? "  · owner" : "") + (uid.equals(me) ? "  (you)" : ""));
                actions.add(() -> {
                    if (iOwn && !uid.equals(me)) confirmRemoveMember(note, uid, members.optString(uid));
                });
            }
            Ui.DialogBuilder d = Ui.dialog().title(shared ? "Shared with " + uids.size() + " of " + GroupNotes.MAX : "Share this note")
                    .negative("Close", null).width(440);
            if (shared) {
                d.items(rows, actions).neutral("Leave", () -> confirmLeave(note));
            } else {
                d.message("Invite up to " + (GroupNotes.MAX - 1) + " people by username. Everyone can edit it, "
                        + "and it stays in step on every device, like your own notes do across yours.");
            }
            if (!shared || uids.size() < GroupNotes.MAX) {
                d.positive("Invite people", () -> askPeople("Invite people", "Invite", people -> {
                    GroupNotes.invite(noteId, people);
                    return "Shared. They'll see it in their notes.";
                }));
            }
            d.show();
        });
    }

    private static void confirmRemoveMember(NoteEntity note, String uid, String name) {
        Ui.dialog().title("Remove @" + name + "?")
                .message("They keep the copy they have, but won't see changes any more.")
                .positive("Remove", () -> NoteStore.load(s -> {
                    try {
                        GroupNotes.remove(s.byId(note.getId()), uid);
                        return null;
                    } catch (Exception e) {
                        return "Couldn't remove them";
                    }
                }, problem -> {
                    if (problem != null) Ui.toast(problem);
                }))
                .negative("Cancel", null).width(420).show();
    }

    private static void confirmLeave(NoteEntity note) {
        Ui.dialog().title("Leave this group note?")
                .message("You keep it as your own note; the others keep theirs.")
                .positive("Leave", () -> NoteStore.load(s -> {
                    try {
                        GroupNotes.leave(s.byId(note.getId()));
                        return null;
                    } catch (Exception e) {
                        return "Couldn't leave right now";
                    }
                }, problem -> {
                    if (problem != null) Ui.toast(problem);
                }))
                .negative("Cancel", null).width(400).show();
    }

    interface PeopleAction {
        /** Returns the message to show when it worked. */
        String apply(List<ChatSync.Contact> people) throws Exception;
    }

    private static void askPeople(String title, String ok, PeopleAction action) {
        Ui.prompt(title, "Usernames, separated by commas.", "Usernames", null, ok, raw -> NoteStore.load(s -> {
            try {
                return action.apply(ChatSync.resolvePeople(raw));
            } catch (Exception e) {
                return e.getMessage() == null ? "Couldn't do that right now" : e.getMessage();
            }
        }, Ui::toast), null, null, null);
    }

    /** A note sent to me goes away from here (they keep theirs). {@code after} runs once it's gone. */
    static void confirmRemoveReceived(NoteEntity note, Runnable after) {
        Ui.dialog().title("Remove this note?")
                .message("@" + note.getSharedFrom() + " keeps it; you won't get it any more. Save a copy first if you want one.")
                .positive("Remove", () -> NoteStore.load(s -> {
                    try {
                        SentNotes.removeReceived(note);
                        return null;
                    } catch (Exception e) {
                        return "Couldn't remove it right now";
                    }
                }, problem -> {
                    if (problem != null) Ui.toast(problem);
                    else if (after != null) after.run();
                }))
                .negative("Cancel", null).width(420).show();
    }

    /** Their note, as one of mine; then it opens, ready to edit. */
    static void saveCopy(NoteEntity received, boolean open) {
        NoteStore.load(s -> SentNotes.saveCopy(received), id -> {
            Ui.toast("Saved as your own note");
            if (open) NotesApp.replace(NoteData.editor(received.getType(), id));
        });
    }
}
