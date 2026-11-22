#!/usr/bin/env python3
"""Collect MTProto proxies from Telegram channels into proxies.json.

Two ways to read a channel, newest posts first:
1. Through a Telegram account (Telethon), when TG_SESSION, TG_API_ID and TG_API_HASH are set.
   Reads any public channel, including ones that hide posts in the web preview (@vnespiska),
   and sees links hidden behind text and inline buttons. Make the session once with make_session.py.
2. The public web preview (https://t.me/s/<channel>). Used when there is no session or the
   account could not read the channel. Does not work for channels that hide their posts.

From every post it takes proxy links (t.me/proxy?..., tg://proxy?...) and plain text blocks like
"Server: ... Port: ... Secret: ...", and writes the format the app expects:
[{"server": "...", "port": 443, "secret": "..."}]

Usage: update_proxies.py [output] [channel ...]
"""

import asyncio
import base64
import html
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request

CHANNELS = ["mtp4tg", "vnespiska"]
MESSAGES_PER_CHANNEL = 300   # via the account
PAGES_PER_CHANNEL = 5        # via the web preview, each page holds about 20 posts
MAX_PROXIES = 100            # the app checks at most 100
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

LINK_RE = re.compile(r"""(?:tg://|https?://(?:www\.)?(?:t\.me|telegram\.me)/)proxy\?([^"'<>\s]+)""", re.I)
POST_ID_RE = re.compile(r'data-post="[^"/]+/(\d+)"')
HOST_RE = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$|^[0-9A-Fa-f:]{2,39}$")
HEX_RE = re.compile(r"^[0-9A-Fa-f]+$")
SPLIT_LINK_RE = re.compile(r"proxy\?\s+(?=(?:server|host|port|secret)=)", re.I)
# Plain text posts: "Сервер: 1.2.3.4  Порт: 443  Секрет: ee..."
PLAIN_SERVER_RE = re.compile(r"(?:server|host|сервер|хост|адрес|ip)\s*[:=]\s*`?([A-Za-z0-9.:\-]+)", re.I)
PLAIN_PORT_RE = re.compile(r"(?:port|порт)\s*[:=]\s*`?(\d{1,5})", re.I)
PLAIN_SECRET_RE = re.compile(r"(?:secret|секрет|ключ)\s*[:=]\s*`?([A-Za-z0-9_+/=\-]{32,})", re.I)


def normalize_secret(secret):
    """The app wants hex. Some links carry the secret in (url-safe) base64."""
    secret = secret.strip()
    if HEX_RE.match(secret) and len(secret) % 2 == 0:
        return secret.lower()
    try:
        padded = secret + "=" * (-len(secret) % 4)
        raw = base64.urlsafe_b64decode(padded.replace("+", "-").replace("/", "_"))
        return raw.hex()
    except Exception:
        return None


def make_proxy(server, port, secret):
    """Same rules as ProxyPickerActivity.buildProxy, so the app does not drop them later."""
    server = (server or "").strip().rstrip(".")
    secret = normalize_secret(secret or "")
    try:
        port = int(port)
    except (TypeError, ValueError):
        return None
    if not server or len(server) > 253 or not HOST_RE.match(server):
        return None
    if not 1 <= port <= 65535:
        return None
    if not secret or not 32 <= len(secret) <= 600:
        return None
    return {"server": server, "port": port, "secret": secret}


def parse_link(query):
    params = urllib.parse.parse_qs(html.unescape(query))
    server = (params.get("server") or params.get("host") or [""])[0]
    port = (params.get("port") or ["443"])[0]
    secret = (params.get("secret") or [""])[0]
    return make_proxy(server, port, secret)


def clean(text):
    """Drop invisible characters that channels put inside links so Telegram does not make them
    clickable (zero-width spaces, soft hyphens, direction marks...), and glue a link broken
    by a line break right after "proxy?"."""
    text = "".join(" " if ch == " " else ch for ch in (text or "")
                   if unicodedata.category(ch) != "Cf")
    return SPLIT_LINK_RE.sub("proxy?", text)


def visible(text):
    """For the log: hidden characters as <U+200B> etc., line breaks as ⏎."""
    return "".join(f"<U+{ord(ch):04X}>" if unicodedata.category(ch) in ("Cf", "Zs") and ch != " "
                   else "⏎" if ch == "\n" else ch for ch in text)


def proxies_from_text(texts):
    """Proxies from the strings of one post (its text and the URLs of its links and buttons)."""
    texts = [clean(t) for t in texts]
    found = []
    for text in texts:
        for query in LINK_RE.findall(text or ""):
            proxy = parse_link(query)
            if proxy:
                found.append(proxy)
    if found:
        return found
    # No links: maybe the post lists the proxy as plain text
    body = "\n".join(t for t in texts if t)
    servers = PLAIN_SERVER_RE.findall(body)
    ports = PLAIN_PORT_RE.findall(body)
    secrets = PLAIN_SECRET_RE.findall(body)
    if servers and len(servers) == len(secrets) and len(ports) in (0, len(servers)):
        for i, server in enumerate(servers):
            proxy = make_proxy(server, ports[i] if ports else 443, secrets[i])
            if proxy:
                found.append(proxy)
    return found


# ---- through a Telegram account ----

def deep_strings(value, with_bytes=False, depth=0):
    """Every string inside a to_dict() tree, in order, without the "_" type names."""
    if depth > 60:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, (bytes, bytearray)):
        return [bytes(value).decode("utf-8", "ignore")] if with_bytes else []
    out = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key != "_":
                out.extend(deep_strings(item, with_bytes, depth + 1))
    elif isinstance(value, (list, tuple)):
        for item in value:
            out.extend(deep_strings(item, with_bytes, depth + 1))
    return out


def message_strings(message):
    """Text of a Telethon message plus every URL hidden in its entities and inline buttons."""
    strings = [message.message or ""]
    for entity in message.entities or []:
        url = getattr(entity, "url", None)
        if url:
            strings.append(url)
    # "Rich" posts (tables, quotes, rows of buttons, like @vnespiska) keep all their text and
    # buttons in message.rich_message, and widget posts in message.media: take every string there.
    rich = getattr(message, "rich_message", None)
    if rich is not None:
        parts = deep_strings(rich.to_dict().get("blocks"))
        strings.extend(parts)
        strings.append("\n".join(parts))  # a link split into several text pieces
    media = getattr(message, "media", None)
    if media is not None and type(media).__name__ in ("MessageMediaWidget", "MessageMediaWebPage"):
        strings.extend(deep_strings(media.to_dict(), with_bytes=True))
    markup = message.reply_markup
    for row in getattr(markup, "rows", None) or []:
        for button in getattr(row, "buttons", None) or []:
            # Older layers: KeyboardButtonUrl(url=...); newer: KeyboardInlineButton(type=InlineButtonTypeUrl(url=...))
            kind = getattr(button, "type", None)
            for value in (getattr(button, "url", None), getattr(kind, "url", None),
                          getattr(button, "copy_text", None), getattr(kind, "copy_text", None)):
                if isinstance(value, str) and value:
                    strings.append(value)
    return strings


async def read_with_account(channels, session, api_id, api_hash):
    """{channel: [proxy, ...] newest first} for the channels that could be read."""
    from telethon import TelegramClient
    from telethon.sessions import StringSession

    result = {}
    client = TelegramClient(StringSession(session), api_id, api_hash)
    await client.connect()
    try:
        if not await client.is_user_authorized():
            # Never start an interactive login here: there is nobody to type the code
            print("TG_SESSION is not signed in (expired or revoked?), make a new one with make_session.py",
                  file=sys.stderr)
            return result
        for channel in channels:
            try:
                proxies = []
                read = with_proxies = 0
                missed = []   # posts that mention a proxy link but gave nothing: shown in the log
                async for message in client.iter_messages(channel, limit=MESSAGES_PER_CHANNEL):
                    read += 1
                    strings = message_strings(message)
                    found = proxies_from_text(strings)
                    if found:
                        with_proxies += 1
                        proxies.extend(found)
                    elif any("proxy" in (s or "").lower() for s in strings) and len(missed) < 3:
                        missed.append((message.id, message.date, strings))
                result[channel] = proxies
                print(f"{channel}: {len(proxies)} links via the account "
                      f"({read} posts read, {with_proxies} with proxies)", file=sys.stderr)
                servers = list(dict.fromkeys(p["server"] for p in proxies))
                print(f"{channel}: newest: {', '.join(servers[:5]) or '-'}", file=sys.stderr)
                for message_id, date, strings in missed:
                    print(f"{channel}: post {message_id} ({date:%Y-%m-%d %H:%M}) mentions a proxy but gave none:",
                          file=sys.stderr)
                    for s in strings:
                        if "proxy" in (s or "").lower():
                            print("    " + visible(s[:400]), file=sys.stderr)
            except Exception as e:
                print(f"{channel}: could not read via the account: {e!r}", file=sys.stderr)
    finally:
        await client.disconnect()
    return result


def account_settings():
    session = os.environ.get("TG_SESSION", "").strip()
    api_id = os.environ.get("TG_API_ID", "").strip()
    api_hash = os.environ.get("TG_API_HASH", "").strip()
    if not session:
        return None
    if not api_id.isdigit() or not api_hash:
        print("TG_SESSION is set but TG_API_ID / TG_API_HASH are not, reading the web preview only",
              file=sys.stderr)
        return None
    return session, int(api_id), api_hash


# ---- through the web preview ----

def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read().decode("utf-8", "replace")


def read_web(channel):
    proxies = []
    before = None
    for _ in range(PAGES_PER_CHANNEL):
        url = f"https://t.me/s/{channel}" + (f"?before={before}" if before else "")
        try:
            page = fetch(url)
        except Exception as e:
            print(f"{channel}: {url} failed: {e}", file=sys.stderr)
            break
        found = [p for p in (parse_link(q) for q in LINK_RE.findall(page)) if p]
        # Posts on a page go oldest to newest; we want newest first overall
        proxies.extend(reversed(found))
        ids = [int(i) for i in POST_ID_RE.findall(page)]
        if not ids or min(ids) <= 1:
            break
        before = min(ids)
    print(f"{channel}: {len(proxies)} links via the web preview", file=sys.stderr)
    return proxies


# ---- together ----

def interleave(lists):
    """Newest of each channel first, so one busy channel does not push the others out of the 100."""
    # One post can give the same proxy several times (quote, copy button...): drop repeats inside
    # each channel first, otherwise they eat that channel's turns
    unique = []
    for proxies in lists:
        own = {}
        for proxy in proxies:
            own.setdefault((proxy["server"].lower(), proxy["port"], proxy["secret"]), proxy)
        unique.append(list(own.values()))
    lists = unique
    result, seen = [], set()
    for i in range(max((len(l) for l in lists), default=0)):
        for proxies in lists:
            if i < len(proxies):
                proxy = proxies[i]
                key = (proxy["server"].lower(), proxy["port"], proxy["secret"])
                if key not in seen:
                    seen.add(key)
                    result.append(proxy)
    return result


def main():
    output = sys.argv[1] if len(sys.argv) > 1 else "proxies.json"
    channels = sys.argv[2:] or CHANNELS

    by_channel = {}
    settings = account_settings()
    if settings:
        try:
            by_channel = asyncio.run(read_with_account(channels, *settings))
        except Exception as e:
            print(f"Reading via the account failed: {e!r}", file=sys.stderr)
    else:
        print("No TG_SESSION: reading the web preview (channels that hide posts give nothing)", file=sys.stderr)
    for channel in channels:
        if not by_channel.get(channel):
            by_channel[channel] = read_web(channel)

    result = interleave([by_channel[c] for c in channels])[:MAX_PROXIES]
    if not result:
        # Keep the previous list rather than publishing an empty one when the channels are unreachable
        print("No proxies found, leaving the file as it is", file=sys.stderr)
        return 1
    with open(output, "w", encoding="utf-8") as f:
        f.write("[\n" + ",\n".join("  " + json.dumps(p, ensure_ascii=False) for p in result) + "\n]\n")
    print(f"Wrote {len(result)} proxies to {output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
