package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.HashSet;

// Telezhka: the extra rows at the bottom of a profile's info block: ID, registration (or creation) date and data center.
public class TelezhkaPeerInfo {

    // Known (user id, registration day) points, from the MIT-licensed dataset of github.com/jobians/telegram-id-age
    // (through 2026-09). Ids are not strictly in time order, so the dates were smoothed into a non-decreasing line
    // (isotonic regression, see the commit that added this file); a user id is placed on it by linear interpolation.
    private static final long[] KNOWN_IDS = {
            0L, 2768409L, 7679610L, 11538514L, 15835244L, 23646077L, 38015510L, 44634663L, 46145305L, 54845238L,
            63263518L, 101260938L, 101323197L, 103151531L, 103258382L, 109393468L, 111220210L, 112594714L, 122600695L,
            124872445L, 125828524L, 130029930L, 133909606L, 143445125L, 148670295L, 157242073L, 171295414L, 181783990L,
            222021233L, 225034354L, 278941742L, 285253072L, 294851037L, 297621225L, 328594461L, 337808429L, 341546272L,
            352940995L, 369669043L, 400169472L, 805158066L, 1974255900L, 5022636255L, 5031711230L, 5045293264L,
            5047148663L, 5070164216L, 5106451106L, 5124771193L, 5144324763L, 5149590651L, 5153900870L, 5210565134L,
            5244529493L, 5288930461L, 5308260177L, 5340744210L, 5433708969L, 5434011049L, 5442755368L, 5505809357L,
            5515826405L, 5546930145L, 5558980075L, 5598262640L, 5601951167L, 5627539474L, 5735455201L, 5738347976L,
            5795660441L, 5802659303L, 5862080962L, 5869978651L, 5983753471L, 5994561143L, 6277658932L, 6326011828L,
            6401027363L, 6523424924L, 6536173556L, 6545049031L, 6559717847L, 6829119388L, 6854829938L, 6872061796L,
            6947316117L, 7002435197L, 7078066115L, 7104310277L, 7224009547L, 7242296450L, 7243375923L, 7254607307L,
            7273085448L, 7293965553L, 7342300216L, 7409259451L, 7450316621L, 7458668365L, 7591351660L, 7793034911L,
            7817256746L, 7832006200L, 7834356221L, 7899152800L, 7912577935L, 8179125032L, 8200159552L, 8209194945L,
            8238766847L, 8267738680L, 8393200797L, 8398217596L, 8461579295L, 8461610757L, 8520261592L, 8530086475L,
            8575281538L, 8581974293L, 8623911057L, 8626758231L, 8635877388L, 8743930536L, 8924993066L, 8969605793L,
            8976922282L
    };
    // days since 1970-01-01
    private static final int[] KNOWN_DAYS = {
            15931, 16010, 16070, 16102, 16121, 16127, 16130, 16196, 16205, 16333, 16370, 16500, 16507, 16585, 16585,
            16601, 16601, 16647, 16647, 16664, 16697, 16697, 16715, 16770, 16792, 16792, 16869, 16901, 16960, 16970,
            17054, 17092, 17124, 17151, 17194, 17218, 17219, 17221, 17256, 17378, 18092, 18912, 18969, 18969, 19005,
            19049, 19049, 19051, 19051, 19051, 19051, 19064, 19064, 19079, 19079, 19107, 19163, 19163, 19172, 19195,
            19195, 19216, 19216, 19264, 19264, 19267, 19274, 19274, 19336, 19336, 19381, 19381, 19408, 19408, 19477,
            19477, 19545, 19664, 19664, 19721, 19721, 19744, 19744, 19754, 19765, 19765, 19819, 19886, 19886, 19905,
            19905, 19922, 19922, 19949, 19949, 19976, 19976, 19998, 19998, 20097, 20097, 20121, 20121, 20251, 20251,
            20280, 20280, 20343, 20343, 20343, 20388, 20388, 20419, 20419, 20434, 20434, 20464, 20464, 20470, 20579,
            20579, 20593, 20593, 20659, 20659, 20701
    };

    public static final int DATE_NONE = 0;
    public static final int DATE_EXACT_MONTH = 1; // from Telegram itself (peer settings of a new chat)
    public static final int DATE_APPROX = 2; // estimated from the user id
    public static final int DATE_EXACT_DAY = 3; // creation of a group or channel
    public static final int DATE_LOADING = 4;

    public static class Date {
        public int kind = DATE_NONE;
        public long seconds;
    }

    // channel id -> creation date in seconds, 0 when it could not be found (message 1 deleted, private channel,
    // network error); kept for the app session so a profile never asks twice
    private static final HashMap<Long, Integer> channelCreated = new HashMap<>();
    private static final HashSet<Long> channelLoading = new HashSet<>();

    // Bot API style: users as is, basic groups -id, channels and supergroups -100id
    public static String formatId(TLRPC.User user, TLRPC.Chat chat) {
        if (user != null) {
            return String.valueOf(user.id);
        }
        if (chat != null) {
            if (ChatObject.isChannel(chat)) {
                return "-100" + chat.id;
            }
            return String.valueOf(-chat.id);
        }
        return "";
    }

    public static int getDc(int account, TLRPC.User user, TLRPC.UserFull userFull, TLRPC.Chat chat) {
        if (user != null) {
            if (UserObject.isUserSelf(user)) {
                return ConnectionsManager.getInstance(account).getCurrentDatacenterId();
            }
            // a personal photo is one we set for this contact, it sits on our own DC
            if (user.photo != null && !user.photo.personal && user.photo.dc_id > 0) {
                return user.photo.dc_id;
            }
            if (userFull != null && userFull.profile_photo != null && userFull.profile_photo.dc_id > 0) {
                return userFull.profile_photo.dc_id;
            }
            return 0;
        }
        if (chat != null && chat.photo != null && chat.photo.dc_id > 0) {
            return chat.photo.dc_id;
        }
        return 0;
    }

    public static String formatDc(int dc) {
        if (dc <= 0) {
            return LocaleController.getString(R.string.TelezhkaInfoUnknown);
        }
        final String place;
        switch (dc) {
            case 1:
            case 3:
                place = LocaleController.getString(R.string.TelezhkaInfoDcMiami);
                break;
            case 2:
            case 4:
                place = LocaleController.getString(R.string.TelezhkaInfoDcAmsterdam);
                break;
            case 5:
                place = LocaleController.getString(R.string.TelezhkaInfoDcSingapore);
                break;
            default:
                place = null;
        }
        return TextUtils.isEmpty(place) ? "DC " + dc : "DC " + dc + " · " + place;
    }

    // Account registration for users, creation for groups and channels. For a channel the date is loaded once
    // (message 1 is the "channel created" service message); onLoaded runs on the UI thread when it arrives.
    public static Date getDate(int account, TLRPC.User user, TLRPC.UserFull userFull, TLRPC.Chat chat, Runnable onLoaded) {
        final Date result = new Date();
        if (user != null) {
            final TLRPC.PeerSettings settings = userFull != null ? userFull.settings : null;
            if (settings != null && settings.registration_month != null) {
                final long seconds = parseMonth(settings.registration_month);
                if (seconds > 0) {
                    result.kind = DATE_EXACT_MONTH;
                    result.seconds = seconds;
                    return result;
                }
            }
            if (user.id > 0 && user.id < 10_000_000_000L) {
                result.kind = DATE_APPROX;
                result.seconds = estimateRegistration(user.id);
            }
            return result;
        }
        if (chat == null) {
            return result;
        }
        if (!ChatObject.isChannel(chat)) {
            // for a basic group "date" is the creation date
            if (chat.date > 0) {
                result.kind = DATE_EXACT_DAY;
                result.seconds = chat.date;
            }
            return result;
        }
        final Integer created = channelCreated.get(chat.id);
        if (created != null) {
            if (created > 0) {
                result.kind = DATE_EXACT_DAY;
                result.seconds = created;
            }
            return result;
        }
        result.kind = DATE_LOADING;
        loadChannelCreated(account, chat, onLoaded);
        return result;
    }

    public static String formatDate(Date date) {
        switch (date.kind) {
            case DATE_EXACT_MONTH:
                return LocaleController.formatYearMont(date.seconds, true);
            case DATE_APPROX:
                return LocaleController.formatString(R.string.TelezhkaInfoApprox, LocaleController.formatYearMont(date.seconds, true));
            case DATE_EXACT_DAY:
                return LocaleController.formatYearMonthDay(date.seconds, true);
            case DATE_LOADING:
                return LocaleController.getString(R.string.TelezhkaInfoLoading);
            default:
                return LocaleController.getString(R.string.TelezhkaInfoUnknown);
        }
    }

    // registration_month comes as "MM.YYYY"
    private static long parseMonth(String value) {
        try {
            final String[] parts = value.split("\\.");
            if (parts.length != 2) {
                return 0;
            }
            final java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.clear();
            calendar.set(Integer.parseInt(parts[1]), Integer.parseInt(parts[0]) - 1, 1);
            return calendar.getTimeInMillis() / 1000L;
        } catch (Exception e) {
            return 0;
        }
    }

    public static long estimateRegistration(long userId) {
        final int n = KNOWN_IDS.length;
        double day;
        if (userId <= KNOWN_IDS[0]) {
            day = KNOWN_DAYS[0];
        } else if (userId >= KNOWN_IDS[n - 1]) {
            day = KNOWN_DAYS[n - 1];
        } else {
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                final int mid = (lo + hi) >>> 1;
                if (KNOWN_IDS[mid] <= userId) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            final double t = (double) (userId - KNOWN_IDS[lo]) / (double) (KNOWN_IDS[hi] - KNOWN_IDS[lo]);
            day = KNOWN_DAYS[lo] + t * (KNOWN_DAYS[hi] - KNOWN_DAYS[lo]);
        }
        return (long) (day * 86400L);
    }

    private static void loadChannelCreated(int account, TLRPC.Chat chat, Runnable onLoaded) {
        final long channelId = chat.id;
        if (!channelLoading.add(channelId)) {
            return;
        }
        final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
        req.channel = MessagesController.getInputChannel(chat);
        req.id.add(1);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            channelLoading.remove(channelId);
            int created = 0;
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    if (message != null && message.id == 1 && message.date > 0) {
                        created = message.date;
                        break;
                    }
                }
            }
            channelCreated.put(channelId, created);
            if (onLoaded != null) {
                onLoaded.run();
            }
        }));
    }
}
