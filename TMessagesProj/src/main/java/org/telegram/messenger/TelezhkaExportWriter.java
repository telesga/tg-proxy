/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Writes an exported chat ("Сохранить переписку") as plain text or as one self-contained HTML page.
 * Plain Java on purpose: TelezhkaExport turns Telegram messages into {@link Item}s, this class only formats them,
 * so it can be tried on a computer without the app. The file itself is in Russian, like the rest of Telezhka.
 */
public class TelezhkaExportWriter {

    public static final int KIND_PRIVATE = 0;
    public static final int KIND_GROUP = 1;
    public static final int KIND_CHANNEL = 2;

    public static final int SPAN_BOLD = 1;
    public static final int SPAN_ITALIC = 2;
    public static final int SPAN_UNDERLINE = 3;
    public static final int SPAN_STRIKE = 4;
    public static final int SPAN_CODE = 5;
    public static final int SPAN_PRE = 6;
    public static final int SPAN_LINK = 7;       // url: where it leads; the text may differ
    public static final int SPAN_SPOILER = 8;
    public static final int SPAN_QUOTE = 9;
    public static final int SPAN_TAG = 10;       // hashtag, cashtag, bot command

    /** Formatting of a part of the text; start and end are UTF-16 offsets, like Telegram's entities. */
    public static class Span {
        public int type;
        public int start;
        public int end;
        public String url;

        public Span(int type, int start, int end, String url) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.url = url;
        }
    }

    public static class Item {
        public int id;
        public long date;            // unix seconds
        public boolean out;
        public boolean service;      // "Вася вступил в группу" and such: shown in the middle, no author
        public long authorId;        // user id, or -chat id; picks the name colour
        public String author = "";
        public String text = "";
        public ArrayList<Span> spans = new ArrayList<>();
        public String mediaIcon;     // an emoji
        public String media;         // "Фото", "Файл: отчёт.pdf · 1,2 МБ"
        public String mediaLink;     // optional: a map link for places
        public ArrayList<String> pollOptions;
        public int replyTo;
        public String forwardFrom;
        public boolean edited;
    }

    public static class Meta {
        public String title = "";
        public int kind = KIND_PRIVATE;
        public long exportedAt;      // unix seconds
        public long since;           // unix seconds, 0 = the whole chat
    }

    private static final Locale RU = new Locale("ru");
    private static final String[] NAME_COLORS = {"#e17076", "#eda86c", "#a695e7", "#7bc862", "#6ec9cb", "#65aadd", "#ee7aae"};

    private final SimpleDateFormat dayFormat = new SimpleDateFormat("d MMMM yyyy", RU);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", RU);
    private final SimpleDateFormat fullFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm", RU);
    private final SimpleDateFormat dayKey = new SimpleDateFormat("yyyyMMdd", RU);

    private String day(long date) {
        return dayFormat.format(new Date(date * 1000L));
    }

    private String time(long date) {
        return timeFormat.format(new Date(date * 1000L));
    }

    private String dayKey(long date) {
        return dayKey.format(new Date(date * 1000L));
    }

    /** Records must be oldest first. */
    public void write(Writer out, Meta meta, List<Item> records, boolean html) throws IOException {
        if (html) {
            writeHtml(out, meta, records);
        } else {
            writeTxt(out, meta, records);
        }
        out.flush();
    }

    // ---------- common ----------

    public static String messagesCount(int n) {
        int mod10 = n % 10, mod100 = n % 100;
        String word = mod10 == 1 && mod100 != 11 ? "сообщение"
                : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "сообщения" : "сообщений";
        return String.format(RU, "%,d", n).replace('\u00A0', ' ').replace('\u202F', ' ') + " " + word;
    }

    private String subtitle(Meta meta, List<Item> records) {
        StringBuilder b = new StringBuilder(messagesCount(countMessages(records)));
        if (!records.isEmpty()) {
            String first = day(records.get(0).date), last = day(records.get(records.size() - 1).date);
            b.append(" · ").append(first.equals(last) ? first : first + " — " + last);
        }
        return b.toString();
    }

    private static int countMessages(List<Item> records) {
        int n = 0;
        for (Item r : records) {
            if (!r.service) {
                n++;
            }
        }
        return n;
    }

    private static String snippet(Item r) {
        String s = r.text != null && !r.text.trim().isEmpty() ? r.text.trim() : (r.media != null ? r.media : "");
        s = s.replace('\n', ' ');
        return s.length() > 90 ? s.substring(0, 87) + "…" : s;
    }

    private static HashMap<Integer, Item> byId(List<Item> records) {
        HashMap<Integer, Item> map = new HashMap<>();
        for (Item r : records) {
            map.put(r.id, r);
        }
        return map;
    }

    // ---------- TXT ----------

    private void writeTxt(Writer out, Meta meta, List<Item> records) throws IOException {
        HashMap<Integer, Item> ids = byId(records);
        out.write("Переписка: " + meta.title + "\n");
        out.write(subtitle(meta, records) + "\n");
        out.write("Сохранено в Тележке " + fullFormat.format(new Date(meta.exportedAt * 1000L)) + "\n");
        out.write("────────────────────────────────────────\n");
        String lastDay = null;
        for (Item r : records) {
            String key = dayKey(r.date);
            if (!key.equals(lastDay)) {
                lastDay = key;
                out.write("\n—— " + day(r.date) + " ——\n\n");
            }
            if (r.service) {
                out.write("[" + time(r.date) + "] · " + r.text + "\n");
                continue;
            }
            StringBuilder head = new StringBuilder();
            head.append('[').append(time(r.date)).append("] ").append(r.author.isEmpty() ? "—" : r.author);
            if (r.edited) {
                head.append(" (изменено)");
            }
            head.append(':');
            out.write(head + "\n");
            if (r.forwardFrom != null) {
                out.write("  ↳ переслано от " + r.forwardFrom + "\n");
            }
            if (r.replyTo != 0) {
                Item target = ids.get(r.replyTo);
                out.write(target != null
                        ? "  ↪ в ответ " + target.author + ": «" + snippet(target) + "»\n"
                        : "  ↪ в ответ на сообщение\n");
            }
            if (r.media != null) {
                out.write("  [" + r.media + (r.mediaLink != null ? " " + r.mediaLink : "") + "]\n");
            }
            if (r.pollOptions != null) {
                for (String option : r.pollOptions) {
                    out.write("    ○ " + option + "\n");
                }
            }
            String text = textWithLinks(r);
            if (!text.isEmpty()) {
                out.write("  " + text.replace("\n", "\n  ") + "\n");
            }
            out.write("\n");
        }
    }

    // links whose text differs from the address get it in brackets: "здесь (https://…)"
    private static String textWithLinks(Item r) {
        String text = r.text == null ? "" : r.text;
        if (r.spans.isEmpty()) {
            return text;
        }
        ArrayList<Span> links = new ArrayList<>();
        for (Span s : r.spans) {
            if (s.type == SPAN_LINK && s.url != null && s.end <= text.length() && s.start < s.end
                    && !text.substring(s.start, s.end).equals(s.url)) {
                links.add(s);
            }
        }
        Collections.sort(links, (a, b) -> Integer.compare(b.end, a.end));
        StringBuilder b = new StringBuilder(text);
        for (Span s : links) {
            b.insert(s.end, " (" + s.url + ")");
        }
        return b.toString();
    }

    // ---------- HTML ----------

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    // only links that cannot run scripts
    private static String safeUrl(String url) {
        if (url == null) {
            return "#";
        }
        String u = url.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("tg://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
            return u;
        }
        return "https://" + u;  // "example.com", and anything else becomes a harmless web address
    }

    private static String openTag(Span s) {
        switch (s.type) {
            case SPAN_BOLD: return "<b>";
            case SPAN_ITALIC: return "<i>";
            case SPAN_UNDERLINE: return "<u>";
            case SPAN_STRIKE: return "<s>";
            case SPAN_CODE: return "<code>";
            case SPAN_PRE: return "<pre>";
            case SPAN_LINK: return "<a href=\"" + esc(safeUrl(s.url)) + "\" target=\"_blank\" rel=\"noopener\">";
            case SPAN_SPOILER: return "<span class=\"sp\" tabindex=\"0\">";
            case SPAN_QUOTE: return "<blockquote>";
            case SPAN_TAG: return "<span class=\"tag\">";
            default: return "<span>";
        }
    }

    private static String closeTag(Span s) {
        switch (s.type) {
            case SPAN_BOLD: return "</b>";
            case SPAN_ITALIC: return "</i>";
            case SPAN_UNDERLINE: return "</u>";
            case SPAN_STRIKE: return "</s>";
            case SPAN_CODE: return "</code>";
            case SPAN_PRE: return "</pre>";
            case SPAN_LINK: return "</a>";
            case SPAN_QUOTE: return "</blockquote>";
            default: return "</span>";
        }
    }

    /** Text with its formatting as HTML; overlapping spans are closed and reopened so tags always nest. */
    static String renderText(String text, List<Span> spans) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int n = text.length();
        ArrayList<Span> valid = new ArrayList<>();
        for (Span s : spans) {
            if (s.start >= 0 && s.end <= n && s.start < s.end) {
                valid.add(s);
            }
        }
        // longer spans first, so they end up outside
        Collections.sort(valid, (a, b) -> a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(b.end, a.end));
        StringBuilder out = new StringBuilder(n + 64);
        ArrayList<Span> stack = new ArrayList<>();
        int next = 0;
        int blockClosedAt = -1;
        for (int i = 0; i <= n; i++) {
            // close what ends here, reopening anything that was opened inside it and goes on
            boolean ends = false;
            for (Span s : stack) {
                if (s.end == i) {
                    ends = true;
                    break;
                }
            }
            if (ends) {
                ArrayList<Span> reopen = new ArrayList<>();
                while (!stack.isEmpty()) {
                    Span top = stack.remove(stack.size() - 1);
                    out.append(closeTag(top));
                    if (top.end == i && (top.type == SPAN_QUOTE || top.type == SPAN_PRE)) {
                        blockClosedAt = i;
                    }
                    if (top.end != i) {
                        reopen.add(0, top);
                    }
                    boolean more = false;
                    for (Span s : stack) {
                        if (s.end == i) {
                            more = true;
                            break;
                        }
                    }
                    if (!more) {
                        break;
                    }
                }
                for (Span s : reopen) {
                    out.append(openTag(s));
                    stack.add(s);
                }
            }
            if (i == n) {
                break;
            }
            while (next < valid.size() && valid.get(next).start == i) {
                Span s = valid.get(next++);
                out.append(openTag(s));
                stack.add(s);
            }
            char c = text.charAt(i);
            boolean inPre = false;
            for (Span s : stack) {
                if (s.type == SPAN_PRE) {
                    inPre = true;
                    break;
                }
            }
            if (c == '\n' && !inPre && (blockClosedAt == i || blockStartsAt(valid, i + 1))) {
                // a quote or code block is already on its own line; this line break would add an empty one
            } else if (c == '\n' && !inPre) {
                out.append("<br>");
            } else {
                switch (c) {
                    case '<': out.append("&lt;"); break;
                    case '>': out.append("&gt;"); break;
                    case '&': out.append("&amp;"); break;
                    case '"': out.append("&quot;"); break;
                    default: out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static boolean blockStartsAt(List<Span> spans, int position) {
        for (Span s : spans) {
            if (s.start == position && (s.type == SPAN_QUOTE || s.type == SPAN_PRE)) {
                return true;
            }
        }
        return false;
    }

    private static String initial(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "?";
        }
        int cp = name.trim().codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(cp)));
    }

    private static String color(long authorId) {
        return NAME_COLORS[(int) (Math.abs(authorId) % NAME_COLORS.length)];
    }

    private void writeHtml(Writer out, Meta meta, List<Item> records) throws IOException {
        HashMap<Integer, Item> ids = byId(records);
        boolean showNames = meta.kind == KIND_GROUP;
        out.write("<!doctype html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta name=\"generator\" content=\"Тележка\">\n"
                + "<title>" + esc(meta.title) + " — переписка</title>\n<style>\n" + CSS + "</style>\n</head>\n<body>\n");
        out.write("<header class=\"top\"><div class=\"wrap\">"
                + "<div class=\"ava\" style=\"background:" + color(meta.title.hashCode()) + "\">" + esc(initial(meta.title)) + "</div>"
                + "<div class=\"head\"><h1>" + esc(meta.title) + "</h1>"
                + "<p>" + esc(subtitle(meta, records)) + "</p></div>"
                + "<input id=\"q\" type=\"search\" placeholder=\"Поиск\" aria-label=\"Поиск по переписке\">"
                + "</div><div class=\"wrap found\" id=\"found\"></div></header>\n<main class=\"wrap\" id=\"chat\">\n");

        String lastDay = null;
        long lastAuthor = Long.MIN_VALUE;
        long lastDate = 0;
        for (Item r : records) {
            String key = dayKey(r.date);
            if (!key.equals(lastDay)) {
                lastDay = key;
                lastAuthor = Long.MIN_VALUE;
                out.write("<div class=\"day\"><span>" + esc(day(r.date)) + "</span></div>\n");
            }
            if (r.service) {
                lastAuthor = Long.MIN_VALUE;
                out.write("<div class=\"svc\" id=\"m" + r.id + "\"><span>" + esc(r.text) + "</span></div>\n");
                continue;
            }
            boolean right = r.out && meta.kind != KIND_CHANNEL;
            boolean first = r.authorId != lastAuthor || r.date - lastDate > 300;
            lastAuthor = r.authorId;
            lastDate = r.date;

            StringBuilder b = new StringBuilder(256);
            b.append("<div class=\"msg").append(right ? " out" : "").append(first ? " first" : "")
                    .append("\" id=\"m").append(r.id).append("\">");
            b.append("<div class=\"bubble\">");
            // groups: who wrote it; channels: only a post signature, the channel name is in the header
            if (first && (showNames && !right || meta.kind == KIND_CHANNEL && !r.author.isEmpty() && !r.author.equals(meta.title))) {
                b.append("<div class=\"name\" style=\"color:").append(color(r.authorId)).append("\">").append(esc(r.author)).append("</div>");
            }
            if (r.forwardFrom != null) {
                b.append("<div class=\"fwd\">Переслано от <b>").append(esc(r.forwardFrom)).append("</b></div>");
            }
            if (r.replyTo != 0) {
                Item target = ids.get(r.replyTo);
                if (target != null) {
                    b.append("<a class=\"reply\" href=\"#m").append(target.id).append("\" style=\"border-color:").append(color(target.authorId)).append("\">")
                            .append("<b style=\"color:").append(color(target.authorId)).append("\">").append(esc(target.service ? "" : target.author)).append("</b>")
                            .append("<span>").append(esc(snippet(target))).append("</span></a>");
                } else {
                    b.append("<div class=\"reply\"><span>Ответ на сообщение, которого нет в этом файле</span></div>");
                }
            }
            if (r.media != null) {
                b.append(r.mediaLink != null ? "<a class=\"media\" target=\"_blank\" rel=\"noopener\" href=\"" + esc(safeUrl(r.mediaLink)) + "\">" : "<div class=\"media\">");
                b.append("<span class=\"ico\">").append(esc(r.mediaIcon != null ? r.mediaIcon : "📎")).append("</span>");
                b.append("<span>").append(esc(r.media)).append("</span>");
                b.append(r.mediaLink != null ? "</a>" : "</div>");
            }
            if (r.pollOptions != null && !r.pollOptions.isEmpty()) {
                b.append("<ul class=\"poll\">");
                for (String option : r.pollOptions) {
                    b.append("<li>").append(esc(option)).append("</li>");
                }
                b.append("</ul>");
            }
            String text = renderText(r.text, r.spans);
            if (!text.isEmpty()) {
                b.append("<div class=\"text\">").append(text).append("</div>");
            }
            b.append("<div class=\"meta\">");
            if (r.edited) {
                b.append("изменено · ");
            }
            b.append("<a href=\"#m").append(r.id).append("\" title=\"").append(esc(fullFormat.format(new Date(r.date * 1000L)))).append("\">")
                    .append(time(r.date)).append("</a></div>");
            b.append("</div></div>\n");
            out.write(b.toString());
        }
        if (records.isEmpty()) {
            out.write("<div class=\"svc\"><span>За выбранное время сообщений нет</span></div>\n");
        }
        out.write("</main>\n<footer class=\"wrap foot\">Сохранено в Тележке "
                + esc(fullFormat.format(new Date(meta.exportedAt * 1000L))) + "</footer>\n");
        out.write("<script>\n" + JS + "</script>\n</body>\n</html>\n");
    }

    private static final String CSS = ""
            + ":root{--bg:#e7ebf0;--panel:#fff;--in:#fff;--out:#e1f7d0;--text:#111;--mute:#7c8590;--link:#2a7ac0;--accent:#ff6b3d;--day:rgba(0,0,0,.28);--line:rgba(0,0,0,.08)}\n"
            + "@media (prefers-color-scheme:dark){:root{--bg:#0d0f14;--panel:#171a21;--in:#1c1f28;--out:#3a2a22;--text:#f2f3f6;--mute:#8e95a4;--link:#7fb6ff;--day:rgba(255,255,255,.12);--line:rgba(255,255,255,.08)}}\n"
            + "*{box-sizing:border-box}html{scroll-padding-top:96px}\n"
            + "body{margin:0;background:var(--bg);color:var(--text);font:15px/1.42 -apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,\"Helvetica Neue\",Arial,sans-serif;-webkit-text-size-adjust:100%}\n"
            + ".wrap{max-width:760px;margin:0 auto;padding:0 12px}\n"
            + ".top{position:sticky;top:0;z-index:5;background:var(--panel);border-bottom:1px solid var(--line)}\n"
            + ".top .wrap:first-child{display:flex;align-items:center;gap:12px;padding-top:10px;padding-bottom:10px}\n"
            + ".ava{flex:none;width:42px;height:42px;border-radius:50%;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:600;font-size:18px}\n"
            + ".head{flex:1;min-width:0}.head h1{margin:0;font-size:17px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n"
            + ".head p{margin:2px 0 0;color:var(--mute);font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n"
            + "#q{flex:none;width:150px;padding:8px 12px;border-radius:18px;border:1px solid var(--line);background:var(--bg);color:var(--text);font:inherit;font-size:14px;outline:none}\n"
            + "#q:focus{border-color:var(--accent)}.found{color:var(--mute);font-size:12px;padding-bottom:6px}.found:empty{display:none}\n"
            + "main{padding-top:8px;padding-bottom:24px}\n"
            + ".day{position:sticky;top:72px;z-index:2;text-align:center;margin:12px 0 6px;pointer-events:none}\n"
            + ".day span,.svc span{display:inline-block;background:var(--day);color:#fff;font-size:13px;padding:3px 10px;border-radius:12px}\n"
            + ".svc{text-align:center;margin:8px 0}.svc span{max-width:90%}\n"
            + ".msg{display:flex;margin:2px 0}.msg.first{margin-top:8px}.msg.out{justify-content:flex-end}\n"
            + ".bubble{position:relative;max-width:min(560px,86%);background:var(--in);border-radius:14px;padding:6px 10px 6px;box-shadow:0 1px 1px rgba(0,0,0,.08);word-wrap:break-word;overflow-wrap:anywhere}\n"
            + ".out .bubble{background:var(--out)}.msg.first .bubble{border-top-left-radius:6px}.msg.out.first .bubble{border-top-left-radius:14px;border-top-right-radius:6px}\n"
            + ".msg:target .bubble{outline:2px solid var(--accent)}\n"
            + ".name{font-weight:600;font-size:14px;margin-bottom:2px}\n"
            + ".fwd{color:var(--link);font-size:13px;margin-bottom:3px}\n"
            + ".reply{display:block;border-left:3px solid var(--accent);background:var(--line);border-radius:6px;padding:3px 8px;margin:2px 0 5px;text-decoration:none;color:inherit;font-size:13px}\n"
            + ".reply b{display:block;font-weight:600}.reply span{display:block;color:var(--mute);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n"
            + ".media{display:flex;align-items:center;gap:8px;background:var(--line);border-radius:10px;padding:7px 10px;margin:3px 0;color:inherit;text-decoration:none;font-size:14px}\n"
            + ".media .ico{font-size:18px;line-height:1}\n"
            + ".poll{margin:4px 0;padding-left:20px}.poll li{margin:2px 0}\n"
            + ".text{white-space:normal}.text a{color:var(--link)}\n"
            + ".text pre{margin:4px 0;padding:8px;border-radius:8px;background:var(--line);overflow-x:auto;white-space:pre-wrap;font:13px/1.4 ui-monospace,Menlo,Consolas,monospace}\n"
            + ".text code{font:13px ui-monospace,Menlo,Consolas,monospace;background:var(--line);padding:1px 4px;border-radius:4px}\n"
            + ".text blockquote{margin:4px 0;padding:2px 8px;border-left:3px solid var(--accent);background:var(--line);border-radius:4px}\n"
            + ".tag{color:var(--link)}\n"
            + ".sp{background:var(--mute);color:transparent;border-radius:4px;cursor:pointer;transition:all .2s}.sp:hover,.sp:focus,.sp.open{background:none;color:inherit}\n"
            + ".meta{float:right;margin:4px 0 -2px 10px;font-size:12px;color:var(--mute);white-space:nowrap}.meta a{color:inherit;text-decoration:none}\n"
            + ".bubble::after{content:\"\";display:block;clear:both}\n"
            + ".hide{display:none!important}mark{background:#ffd54a;color:#111;border-radius:2px}\n"
            + ".foot{color:var(--mute);font-size:12px;text-align:center;padding-bottom:28px}\n"
            + "@media (max-width:480px){#q{width:110px}.bubble{max-width:90%}}\n";

    // search: hides messages without the words and shows how many are left
    private static final String JS = ""
            + "(function(){var q=document.getElementById('q'),f=document.getElementById('found'),\n"
            + "items=[].slice.call(document.querySelectorAll('#chat .msg,#chat .svc,#chat .day')),t;\n"
            + "document.addEventListener('click',function(e){if(e.target.classList.contains('sp'))e.target.classList.toggle('open')});\n"
            + "q.addEventListener('input',function(){clearTimeout(t);t=setTimeout(run,150)});\n"
            + "function run(){var s=q.value.trim().toLowerCase(),n=0;\n"
            + "items.forEach(function(el){if(!s){el.classList.remove('hide');return}\n"
            + "if(el.classList.contains('day')){el.classList.add('hide');return}\n"
            + "var hit=el.textContent.toLowerCase().indexOf(s)>=0;el.classList.toggle('hide',!hit);if(hit)n++});\n"
            + "f.textContent=s?(n?'Найдено: '+n:'Ничего не найдено'):''}})();\n";
}
