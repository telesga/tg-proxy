/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * "Сохранить переписку": loads a chat's history from Telegram page by page (100 messages a request,
 * waiting out FLOOD_WAIT when Telegram asks to slow down) and writes it with TelezhkaExportWriter
 * as .txt or .html into the cache, from where it is shared or saved to Downloads.
 * The file has what Telegram has: local edits ("Изменить у себя") are not in it.
 */
public class TelezhkaExport {

    public interface Listener {
        /** progress 0..1, or -1 when the total is not known */
        void onProgress(int loaded, float progress);

        void onWait(int seconds);

        void onDone(File file, int count);

        void onError(String error);
    }

    private static final int PAGE = 100;
    private static final int PAUSE_MS = 150;
    private static final int MAX_RETRIES = 3;

    private final int account;
    private final long dialogId;
    private final String title;
    private final int kind;
    private final boolean html;
    private final int since;  // unix seconds, 0 = everything
    private final Listener listener;

    private final ArrayList<TelezhkaExportWriter.Item> items = new ArrayList<>();
    private final HashMap<Long, TLRPC.User> users = new HashMap<>();
    private final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
    private volatile boolean cancelled;
    private volatile int requestId;
    private int total;
    private int newestDate;
    private int retries;

    public TelezhkaExport(int account, long dialogId, String title, int kind, boolean html, int since, Listener listener) {
        this.account = account;
        this.dialogId = dialogId;
        this.title = title;
        this.kind = kind;
        this.html = html;
        this.since = since;
        this.listener = listener;
    }

    public void start() {
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self != null) {
            users.put(self.id, self);
        }
        loadPage(0);
    }

    public void cancel() {
        cancelled = true;
        if (requestId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        }
    }

    private void loadPage(int offsetId) {
        if (cancelled) {
            return;
        }
        TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        if (peer == null) {
            fail("no peer");
            return;
        }
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.offset_id = offsetId;
        req.limit = PAGE;
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            requestId = 0;
            if (cancelled) {
                return;
            }
            if (error != null) {
                int wait = floodWait(error.text);
                if (wait > 0) {
                    // Telegram asks to slow down: wait as long as it says, then go on from the same place
                    AndroidUtilities.runOnUIThread(() -> listener.onWait(wait));
                    Utilities.globalQueue.postRunnable(() -> loadPage(offsetId), (wait + 1) * 1000L);
                } else if (retries++ < MAX_RETRIES) {
                    Utilities.globalQueue.postRunnable(() -> loadPage(offsetId), 2000L * retries);
                } else {
                    fail(error.text);
                }
                return;
            }
            retries = 0;
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            Utilities.globalQueue.postRunnable(() -> onPage(res));
        });
    }

    private static int floodWait(String text) {
        if (text != null && text.startsWith("FLOOD_WAIT_")) {
            try {
                return Math.max(1, Integer.parseInt(text.substring("FLOOD_WAIT_".length())));
            } catch (Exception ignore) {
            }
        }
        return 0;
    }

    private void onPage(TLRPC.messages_Messages res) {
        if (cancelled) {
            return;
        }
        for (TLRPC.User user : res.users) {
            users.put(user.id, user);
        }
        for (TLRPC.Chat chat : res.chats) {
            chats.put(chat.id, chat);
        }
        if (total == 0) {
            total = res instanceof TLRPC.TL_messages_messages ? res.messages.size() : res.count;
        }
        boolean reachedSince = false;
        int lastId = 0;
        for (TLRPC.Message message : res.messages) {
            if (message == null) {
                continue;
            }
            lastId = message.id;  // the next page starts below the oldest one here
            if (message instanceof TLRPC.TL_messageEmpty) {
                continue;
            }
            if (newestDate == 0) {
                newestDate = message.date;
            }
            if (since > 0 && message.date < since) {
                reachedSince = true;
                break;
            }
            try {
                items.add(convert(message));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        // messages.messages (not a slice) means the whole chat came in one answer
        boolean finished = reachedSince || lastId <= 0 || res.messages.isEmpty() || res instanceof TLRPC.TL_messages_messages;
        float progress;
        if (since > 0 && newestDate > since && !res.messages.isEmpty()) {
            TLRPC.Message oldest = res.messages.get(res.messages.size() - 1);
            progress = Math.max(0f, Math.min(1f, (newestDate - oldest.date) / (float) (newestDate - since)));
        } else {
            progress = total > 0 ? Math.min(1f, items.size() / (float) total) : -1;
        }
        int loaded = items.size();
        AndroidUtilities.runOnUIThread(() -> {
            if (!cancelled) {
                listener.onProgress(loaded, finished ? 1f : progress);
            }
        });
        if (finished) {
            write();
        } else {
            final int offset = lastId;
            Utilities.globalQueue.postRunnable(() -> loadPage(offset), PAUSE_MS);
        }
    }

    private void write() {
        if (cancelled) {
            return;
        }
        Collections.reverse(items);  // Telegram gives the newest first
        TelezhkaExportWriter.Meta meta = new TelezhkaExportWriter.Meta();
        meta.title = title;
        meta.kind = kind;
        meta.exportedAt = System.currentTimeMillis() / 1000;
        meta.since = since;
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String name = FileLoader.fixFileName("Тележка — " + title + " — " + day + (html ? ".html" : ".txt"));
        File dir = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "telezhka_export");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                f.delete();  // one export at a time is enough to keep in the cache
            }
        }
        File file = new File(dir, name);
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            new TelezhkaExportWriter().write(out, meta, items, html);
        } catch (Throwable e) {
            FileLog.e(e);
            fail(e.getMessage());
            return;
        }
        int count = 0;
        for (TelezhkaExportWriter.Item item : items) {
            if (!item.service) {
                count++;
            }
        }
        final int finalCount = count;
        AndroidUtilities.runOnUIThread(() -> {
            if (!cancelled) {
                listener.onDone(file, finalCount);
            }
        });
    }

    private void fail(String error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!cancelled) {
                listener.onError(error);
            }
        });
    }

    // ---------- Telegram message → Item ----------

    private TLRPC.User user(long id) {
        TLRPC.User user = users.get(id);
        return user != null ? user : MessagesController.getInstance(account).getUser(id);
    }

    private TLRPC.Chat chat(long id) {
        TLRPC.Chat chat = chats.get(id);
        return chat != null ? chat : MessagesController.getInstance(account).getChat(id);
    }

    private String peerName(TLRPC.Peer peer) {
        if (peer == null) {
            return null;
        }
        if (peer.user_id != 0) {
            TLRPC.User user = user(peer.user_id);
            return user != null ? UserObject.getUserName(user) : null;
        }
        long chatId = peer.channel_id != 0 ? peer.channel_id : peer.chat_id;
        TLRPC.Chat chat = chat(chatId);
        return chat != null ? chat.title : null;
    }

    private static long peerId(TLRPC.Peer peer) {
        if (peer == null) {
            return 0;
        }
        if (peer.user_id != 0) {
            return peer.user_id;
        }
        return -(peer.channel_id != 0 ? peer.channel_id : peer.chat_id);
    }

    private TelezhkaExportWriter.Item convert(TLRPC.Message message) {
        TelezhkaExportWriter.Item item = new TelezhkaExportWriter.Item();
        item.id = message.id;
        item.date = message.date;
        item.out = message.out;

        // author: the sender; a channel post without one is from the channel (plus its signature)
        TLRPC.Peer from = message.from_id != null ? message.from_id : message.peer_id;
        if (message.from_id == null && message.out && kind == TelezhkaExportWriter.KIND_PRIVATE) {
            from = new TLRPC.TL_peerUser();
            from.user_id = UserConfig.getInstance(account).getClientUserId();
        }
        item.authorId = peerId(from);
        String author = peerName(from);
        if (!TextUtils.isEmpty(message.post_author) && (author == null || message.from_id == null || message.from_id.user_id == 0)) {
            author = author != null && kind != TelezhkaExportWriter.KIND_CHANNEL ? author + " (" + message.post_author + ")" : message.post_author;
        }
        item.author = author != null ? author : "";

        if (message instanceof TLRPC.TL_messageService) {
            item.service = true;
            try {
                MessageObject object = new MessageObject(account, message, users, chats, false, false);
                item.text = object.messageText != null ? object.messageText.toString() : "";
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (TextUtils.isEmpty(item.text)) {
                item.text = "Служебное сообщение";
            }
            return item;
        }

        item.text = message.message != null ? message.message : "";
        addSpans(item, message.entities);
        item.edited = message.edit_date != 0 && !message.edit_hide;
        if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0
                && (message.reply_to.reply_to_peer_id == null || peerId(message.reply_to.reply_to_peer_id) == dialogId)) {
            item.replyTo = message.reply_to.reply_to_msg_id;
        }
        if (message.fwd_from != null) {
            String name = peerName(message.fwd_from.from_id);
            if (name == null) {
                name = message.fwd_from.from_name;
            }
            item.forwardFrom = !TextUtils.isEmpty(name) ? name : "скрытого пользователя";
        }
        describeMedia(item, message);
        return item;
    }

    private void addSpans(TelezhkaExportWriter.Item item, ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null) {
            return;
        }
        String text = item.text;
        for (TLRPC.MessageEntity e : entities) {
            int start = e.offset, end = e.offset + e.length;
            if (start < 0 || end > text.length() || start >= end) {
                continue;
            }
            int type = 0;
            String url = null;
            if (e instanceof TLRPC.TL_messageEntityBold) {
                type = TelezhkaExportWriter.SPAN_BOLD;
            } else if (e instanceof TLRPC.TL_messageEntityItalic) {
                type = TelezhkaExportWriter.SPAN_ITALIC;
            } else if (e instanceof TLRPC.TL_messageEntityUnderline) {
                type = TelezhkaExportWriter.SPAN_UNDERLINE;
            } else if (e instanceof TLRPC.TL_messageEntityStrike) {
                type = TelezhkaExportWriter.SPAN_STRIKE;
            } else if (e instanceof TLRPC.TL_messageEntityCode) {
                type = TelezhkaExportWriter.SPAN_CODE;
            } else if (e instanceof TLRPC.TL_messageEntityPre) {
                type = TelezhkaExportWriter.SPAN_PRE;
            } else if (e instanceof TLRPC.TL_messageEntitySpoiler) {
                type = TelezhkaExportWriter.SPAN_SPOILER;
            } else if (e instanceof TLRPC.TL_messageEntityBlockquote) {
                type = TelezhkaExportWriter.SPAN_QUOTE;
            } else if (e instanceof TLRPC.TL_messageEntityTextUrl) {
                type = TelezhkaExportWriter.SPAN_LINK;
                url = e.url;
            } else if (e instanceof TLRPC.TL_messageEntityUrl) {
                type = TelezhkaExportWriter.SPAN_LINK;
                url = text.substring(start, end);
            } else if (e instanceof TLRPC.TL_messageEntityEmail) {
                type = TelezhkaExportWriter.SPAN_LINK;
                url = "mailto:" + text.substring(start, end);
            } else if (e instanceof TLRPC.TL_messageEntityPhone) {
                type = TelezhkaExportWriter.SPAN_LINK;
                url = "tel:" + text.substring(start, end).replaceAll("[^+0-9]", "");
            } else if (e instanceof TLRPC.TL_messageEntityMention) {
                type = TelezhkaExportWriter.SPAN_LINK;
                url = "https://t.me/" + text.substring(start, end).replace("@", "");
            } else if (e instanceof TLRPC.TL_messageEntityMentionName) {
                TLRPC.User user = user(((TLRPC.TL_messageEntityMentionName) e).user_id);
                if (user != null && !TextUtils.isEmpty(UserObject.getPublicUsername(user))) {
                    type = TelezhkaExportWriter.SPAN_LINK;
                    url = "https://t.me/" + UserObject.getPublicUsername(user);
                } else {
                    type = TelezhkaExportWriter.SPAN_TAG;
                }
            } else if (e instanceof TLRPC.TL_messageEntityHashtag || e instanceof TLRPC.TL_messageEntityCashtag
                    || e instanceof TLRPC.TL_messageEntityBotCommand) {
                type = TelezhkaExportWriter.SPAN_TAG;
            }
            if (type != 0) {
                item.spans.add(new TelezhkaExportWriter.Span(type, start, end, url));
            }
        }
    }

    private static String duration(double seconds) {
        int s = (int) Math.round(seconds);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static String coord(double value) {
        return String.format(Locale.US, "%.5f", value);
    }

    private void describeMedia(TelezhkaExportWriter.Item item, TLRPC.Message message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
            return;
        }
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            item.mediaIcon = "📷";
            item.media = media.ttl_seconds != 0 ? "Исчезающее фото" : "Фото";
        } else if (media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document document = media.document;
            double length = 0;
            String alt = null, performer = null, songTitle = null;
            if (document != null) {
                for (TLRPC.DocumentAttribute attribute : document.attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeVideo || attribute instanceof TLRPC.TL_documentAttributeAudio) {
                        length = Math.max(length, attribute.duration);
                    }
                    if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                        performer = attribute.performer;
                        songTitle = attribute.title;
                    }
                    if (attribute instanceof TLRPC.TL_documentAttributeSticker && !TextUtils.isEmpty(attribute.alt)) {
                        alt = attribute.alt;
                    }
                }
            }
            String time = length > 0 ? " · " + duration(length) : "";
            if (MessageObject.isRoundVideoMessage(message)) {
                item.mediaIcon = "⭕";
                item.media = "Видеосообщение" + time;
            } else if (MessageObject.isVoiceMessage(message)) {
                item.mediaIcon = "🎤";
                item.media = "Голосовое" + time;
            } else if (MessageObject.isStickerMessage(message) || MessageObject.isAnimatedStickerMessage(message)) {
                item.mediaIcon = alt != null ? alt : "🏷";
                item.media = "Стикер";
            } else if (MessageObject.isGifMessage(message)) {
                item.mediaIcon = "🎞";
                item.media = "GIF";
            } else if (MessageObject.isVideoMessage(message)) {
                item.mediaIcon = "🎥";
                item.media = "Видео" + time;
            } else if (MessageObject.isMusicMessage(message)) {
                item.mediaIcon = "🎵";
                String song = (TextUtils.isEmpty(performer) ? "" : performer + " — ") + (TextUtils.isEmpty(songTitle) ? "" : songTitle);
                item.media = (song.isEmpty() ? "Аудио" : song) + time;
            } else {
                item.mediaIcon = "📎";
                String fileName = document != null ? FileLoader.getDocumentFileName(document) : null;
                item.media = "Файл" + (TextUtils.isEmpty(fileName) ? "" : ": " + fileName)
                        + (document != null && document.size > 0 ? " · " + AndroidUtilities.formatFileSize(document.size) : "");
            }
        } else if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive || media instanceof TLRPC.TL_messageMediaVenue) {
            item.mediaIcon = "📍";
            String place = media instanceof TLRPC.TL_messageMediaVenue
                    ? (TextUtils.isEmpty(media.title) ? "" : media.title) + (TextUtils.isEmpty(media.address) ? "" : ", " + media.address) : "";
            if (media.geo != null && !(media.geo instanceof TLRPC.TL_geoPointEmpty)) {
                String lat = coord(media.geo.lat), lon = coord(media.geo._long);
                item.mediaLink = "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=17/" + lat + "/" + lon;
                if (place.isEmpty()) {
                    place = lat + ", " + lon;
                }
            }
            item.media = (media instanceof TLRPC.TL_messageMediaGeoLive ? "Трансляция геопозиции" : "Место") + (place.isEmpty() ? "" : ": " + place);
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            item.mediaIcon = "👤";
            String name = ((media.first_name != null ? media.first_name : "") + " " + (media.last_name != null ? media.last_name : "")).trim();
            item.media = "Контакт" + (name.isEmpty() ? "" : ": " + name) + (TextUtils.isEmpty(media.phone_number) ? "" : ", " + media.phone_number);
        } else if (media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll poll = (TLRPC.TL_messageMediaPoll) media;
            item.mediaIcon = "📊";
            item.media = (poll.poll != null && poll.poll.quiz ? "Викторина: " : "Опрос: ")
                    + (poll.poll != null && poll.poll.question != null ? poll.poll.question.text : "");
            item.pollOptions = new ArrayList<>();
            if (poll.poll != null) {
                for (TLRPC.PollAnswer answer : poll.poll.answers) {
                    item.pollOptions.add(answer.text != null ? answer.text.text : "");
                }
            }
        } else if (media instanceof TLRPC.TL_messageMediaDice) {
            TLRPC.TL_messageMediaDice dice = (TLRPC.TL_messageMediaDice) media;
            item.mediaIcon = TextUtils.isEmpty(dice.emoticon) ? "🎲" : dice.emoticon;
            item.media = "Выпало: " + dice.value;
        } else if (media instanceof TLRPC.TL_messageMediaGame) {
            item.mediaIcon = "🎮";
            item.media = "Игра" + (media.game != null && !TextUtils.isEmpty(media.game.title) ? ": " + media.game.title : "");
        } else if (media instanceof TLRPC.TL_messageMediaInvoice) {
            item.mediaIcon = "🧾";
            item.media = "Счёт" + (TextUtils.isEmpty(media.title) ? "" : ": " + media.title);
        } else if (media instanceof TLRPC.TL_messageMediaStory) {
            item.mediaIcon = "📖";
            item.media = "История";
        } else if (media instanceof TLRPC.TL_messageMediaGiveaway || media instanceof TLRPC.TL_messageMediaGiveawayResults) {
            item.mediaIcon = "🎁";
            item.media = "Розыгрыш";
        } else if (media instanceof TLRPC.TL_messageMediaPaidMedia) {
            item.mediaIcon = "⭐";
            item.media = "Платный контент";
        } else {
            item.mediaIcon = "📎";
            item.media = "Вложение";
        }
    }
}
