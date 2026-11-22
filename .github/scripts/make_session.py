#!/usr/bin/env python3
"""Make TG_SESSION for update_proxies.py. Run once on your own computer, not in GitHub Actions.

    pip install "telethon>=1.36,<2"
    python make_session.py

It asks for api_id and api_hash (my.telegram.org), the phone number, the login code and the
two-step password if there is one. Everything is typed here, in your terminal, and goes only to
Telegram. At the end it prints one long line: put it into the repository secret TG_SESSION
(Settings → Secrets and variables → Actions), and api_id / api_hash into TG_API_ID / TG_API_HASH.

Use a separate account, not your main one: the session gives full access to the account it was
made for, and Telegram may restrict accounts that read channels through the API.

Telegram has to be reachable from this computer. If it is blocked, turn on a VPN, or give a
SOCKS5 proxy: TG_SOCKS=127.0.0.1:1080 python make_session.py (needs pip install python-socks).

To revoke the session: Telegram → Settings → Devices, end the session named after this script.
"""

import os

from telethon.sessions import StringSession
from telethon.sync import TelegramClient


def main():
    api_id = int(input("api_id: ").strip())
    api_hash = input("api_hash: ").strip()
    proxy = None
    socks = os.environ.get("TG_SOCKS", "").strip()
    if socks:
        host, _, port = socks.rpartition(":")
        proxy = {"proxy_type": "socks5", "addr": host, "port": int(port)}
    with TelegramClient(StringSession(), api_id, api_hash, proxy=proxy,
                        device_model="Telezhka proxy reader") as client:
        me = client.get_me()
        print()
        print(f"Signed in as {me.first_name} (id {me.id}). TG_SESSION:")
        print()
        print(client.session.save())
        print()
        print("Keep it secret: anyone with this line can use the account.")


if __name__ == "__main__":
    main()
