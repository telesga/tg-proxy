/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.os.SystemClock;

import androidx.collection.LongSparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * "Сохранять удалённые": a deletion can reach the phone in several ways (updates, a push, a gap in the
 * updates after a long time offline...), and some of them never tell Telezhka which messages went away.
 * So when a chat is shown, ask the server about its newest messages: the ones it no longer has were
 * deleted, and get the "удалено" mark, whatever way the deletion took.
 */
public class TelezhkaDeletedCheck {

    private static final int MAX_IDS = 100;
    // Per chat: the chat screen calls this on every redraw
    private static final long THROTTLE_MS = 30 * 1000L;

    private static final HashMap<String, Long> lastCheck = new HashMap<>();

    // UI thread. messages: newest first, as in ChatActivity
    public static void check(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (!TelezhkaPrivacy.keepDeleted() || messages == null || messages.isEmpty() || dialogId == 0
                || DialogObject.isEncryptedDialog(dialogId) || dialogId == BuildVars.MUSIC_BOT_ID) {
            return;
        }
        String throttleKey = account + "_" + dialogId;
        long now = SystemClock.elapsedRealtime();
        Long last = lastCheck.get(throttleKey);
        if (last != null && now - last < THROTTLE_MS) {
            return;
        }
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Chat chat = null;
        if (DialogObject.isChatDialog(dialogId)) {
            chat = controller.getChat(-dialogId);
            if (chat == null) {
                return;
            }
        }
        boolean channel = ChatObject.isChannel(chat);
        ArrayList<Integer> ids = new ArrayList<>();
        for (int i = 0; i < messages.size() && ids.size() < MAX_IDS; i++) {
            MessageObject message = messages.get(i);
            if (message == null || message.messageOwner == null || message.getId() <= 0 || message.scheduled
                    || message.isSending() || message.getDialogId() != dialogId
                    || TelezhkaPrivacy.isDeleted(account, message)) {
                continue;
            }
            ids.add(message.getId());
        }
        if (ids.isEmpty()) {
            return;
        }
        lastCheck.put(throttleKey, now);
        TLObject request;
        if (channel) {
            TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = MessagesController.getInputChannel(chat);
            req.id = ids;
            request = req;
        } else {
            TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
            req.id = ids;
            request = req;
        }
        final long key = channel ? -chat.id : 0;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            HashSet<Integer> alive = new HashSet<>();
            HashSet<Integer> empty = new HashSet<>();
            for (TLRPC.Message message : res.messages) {
                if (message == null) {
                    continue;
                }
                if (message instanceof TLRPC.TL_messageEmpty) {
                    empty.add(message.id);
                } else {
                    alive.add(message.id);
                }
            }
            if (alive.isEmpty() && empty.isEmpty()) {
                return;  // the server said nothing about these messages: do not guess
            }
            ArrayList<Integer> gone = new ArrayList<>();
            for (Integer id : ids) {
                if (!alive.contains(id)) {
                    gone.add(id);
                }
            }
            if (gone.isEmpty()) {
                return;
            }
            MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
                TelezhkaPrivacy.markDeleted(account, key, gone);
                LongSparseArray<ArrayList<Integer>> marked = new LongSparseArray<>();
                marked.put(key, gone);
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.telezhkaMessagesMarkedDeleted, marked));
            });
        });
    }
}
