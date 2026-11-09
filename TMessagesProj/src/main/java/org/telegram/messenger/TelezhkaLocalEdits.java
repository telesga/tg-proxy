/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * "Изменить у себя": the text (or caption) of any message can be changed on this phone only.
 * Nothing is sent anywhere. The new text is kept here and put into the message every time a MessageObject
 * is made from it (chat, chat list, replies, search, notifications); "Вернуть как было" brings the original back.
 *
 * One entry per message, key "account_dialogId_messageId":
 * {"t": text shown, "o": original text, "e": original entities as base64 TL}.
 * The message object may get written to the database with the local text; that is fine, because the entry
 * is checked on every load, and on revert the original is written back.
 */
public class TelezhkaLocalEdits {

    private static final String PREFS = "telezhka_local_edits";

    private static class Entry {
        String text;
        String original;
        String entities;
    }

    private static final Object lock = new Object();
    private static SharedPreferences prefs;
    private static HashMap<String, Entry> entries;
    private static volatile int count = -1;  // -1: not loaded yet

    private static SharedPreferences prefs() {
        if (prefs == null) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    private static HashMap<String, Entry> entries() {
        if (entries == null) {
            entries = new HashMap<>();
            try {
                for (Map.Entry<String, ?> item : prefs().getAll().entrySet()) {
                    if (!(item.getValue() instanceof String)) {
                        continue;
                    }
                    JSONObject json = new JSONObject((String) item.getValue());
                    Entry entry = new Entry();
                    entry.text = json.optString("t");
                    entry.original = json.optString("o");
                    entry.entities = json.optString("e");
                    entries.put(item.getKey(), entry);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            count = entries.size();
        }
        return entries;
    }

    private static String key(int account, long dialogId, int messageId) {
        return account + "_" + dialogId + "_" + messageId;
    }

    private static void save(String key, Entry entry) {
        try {
            if (entry == null) {
                prefs().edit().remove(key).apply();
            } else {
                JSONObject json = new JSONObject();
                json.put("t", entry.text);
                json.put("o", entry.original);
                json.put("e", entry.entities == null ? "" : entry.entities);
                prefs().edit().putString(key, json.toString()).apply();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        count = entries.size();
    }

    private static boolean nothingSaved() {
        if (count < 0) {
            if (ApplicationLoader.applicationContext == null) {
                return true;
            }
            synchronized (lock) {
                entries();
            }
        }
        return count == 0;
    }

    /** Called for every new MessageObject: puts the local text into the message if there is one. */
    public static void apply(int account, TLRPC.Message message) {
        if (message == null || message.id <= 0 || nothingSaved()) {
            return;
        }
        synchronized (lock) {
            String key = key(account, MessageObject.getDialogId(message), message.id);
            Entry entry = entries().get(key);
            if (entry == null) {
                return;
            }
            String current = message.message != null ? message.message : "";
            if (current.equals(entry.text)) {
                return;
            }
            if (!current.equals(entry.original)) {
                // fresh text from Telegram (the message was edited for real): it becomes the original to return to
                entry.original = current;
                entry.entities = encodeEntities(message.entities);
                save(key, entry);
            }
            message.message = entry.text;
            message.entities = new ArrayList<>();
        }
    }

    public static boolean has(int account, MessageObject object) {
        if (object == null || object.messageOwner == null || object.getId() <= 0 || nothingSaved()) {
            return false;
        }
        synchronized (lock) {
            return entries().containsKey(key(account, object.getDialogId(), object.getId()));
        }
    }

    /** Text as Telegram has it, under a possible local edit; null when the message is not edited here. */
    public static String originalText(int account, long dialogId, int messageId) {
        if (nothingSaved()) {
            return null;
        }
        synchronized (lock) {
            Entry entry = entries().get(key(account, dialogId, messageId));
            return entry != null ? entry.original : null;
        }
    }

    /** The real text of a message stored in the database, which may hold the local one instead. */
    public static String realText(int account, long dialogId, int messageId, String stored) {
        if (stored == null || nothingSaved()) {
            return stored;
        }
        synchronized (lock) {
            Entry entry = entries().get(key(account, dialogId, messageId));
            return entry != null && stored.equals(entry.text) ? entry.original : stored;
        }
    }

    /** Shows {@code text} instead of the message text on this phone. Changes object.messageOwner in place. */
    public static void set(int account, MessageObject object, String text) {
        TLRPC.Message message = object.messageOwner;
        String key = key(account, object.getDialogId(), object.getId());
        synchronized (lock) {
            Entry entry = entries().get(key);
            if (entry == null) {
                entry = new Entry();
                entry.original = message.message != null ? message.message : "";
                entry.entities = encodeEntities(message.entities);
            }
            if (text.equals(entry.original)) {
                restore(message, entry);
                entries().remove(key);
                save(key, null);
                return;
            }
            entry.text = text;
            entries().put(key, entry);
            save(key, entry);
            message.message = text;
            message.entities = new ArrayList<>();
        }
    }

    /** Brings the original text back into object.messageOwner; returns false if there was no local edit. */
    public static boolean revert(int account, MessageObject object) {
        String key = key(account, object.getDialogId(), object.getId());
        synchronized (lock) {
            Entry entry = entries().remove(key);
            if (entry == null) {
                return false;
            }
            save(key, null);
            restore(object.messageOwner, entry);
            return true;
        }
    }

    private static void restore(TLRPC.Message message, Entry entry) {
        message.message = entry.original;
        message.entities = decodeEntities(entry.entities);
    }

    private static String encodeEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }
        SerializedData data = null;
        try {
            int size = 4;
            for (TLRPC.MessageEntity entity : entities) {
                size += entity.getObjectSize();
            }
            data = new SerializedData(size);
            data.writeInt32(entities.size());
            for (TLRPC.MessageEntity entity : entities) {
                entity.serializeToStream(data);
            }
            return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    private static ArrayList<TLRPC.MessageEntity> decodeEntities(String encoded) {
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return result;
        }
        SerializedData data = null;
        try {
            data = new SerializedData(Base64.decode(encoded, Base64.NO_WRAP));
            int n = data.readInt32(false);
            for (int i = 0; i < n; i++) {
                TLRPC.MessageEntity entity = TLRPC.MessageEntity.TLdeserialize(data, data.readInt32(false), false);
                if (entity != null) {
                    result.add(entity);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
        return result;
    }

    /** Puts the changed message on screen: the open chat, the chat list, and the database after a revert. */
    public static void refresh(int account, MessageObject old, boolean writeToStorage) {
        TLRPC.Message message = old.messageOwner;
        MessageObject updated = new MessageObject(account, message, old.replyMessageObject, true, true);
        long dialogId = updated.getDialogId();
        MessagesController controller = MessagesController.getInstance(account);
        boolean dialogs = false;
        ArrayList<MessageObject> last = controller.dialogMessage.get(dialogId);
        if (last != null) {
            for (int i = 0; i < last.size(); i++) {
                if (last.get(i).getId() == updated.getId()) {
                    last.set(i, updated);
                    if (message.peer_id != null && message.peer_id.channel_id == 0) {
                        controller.dialogMessagesByIds.put(updated.getId(), updated);
                    }
                    dialogs = true;
                }
            }
        }
        if (writeToStorage) {
            TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
            res.messages.add(message);
            MessagesStorage.getInstance(account).putMessages(res, dialogId, -2, 0, false, 0, 0);
        }
        ArrayList<MessageObject> list = new ArrayList<>();
        list.add(updated);
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.postNotificationName(NotificationCenter.replaceMessagesObjects, dialogId, list, false);
        if (dialogs) {
            center.postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }
}
