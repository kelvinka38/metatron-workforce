"""Login for the preview site through Telegram: the bot sends the founder a one-time link, the link
sets a session cookie. Replaces an emailed code; only the founder's chat ever gets a link."""
from __future__ import annotations

import secrets
import threading
import time

COOKIE = "core_preview"
LOGIN_PATH = "/__core_login"


class PreviewAuth:
    def __init__(self, link_ttl: float = 1800, session_ttl: float = 7 * 86400):
        self.link_ttl, self.session_ttl = link_ttl, session_ttl
        self._links: dict[str, float] = {}
        self._sessions: dict[str, float] = {}
        self._lock = threading.Lock()

    def new_link(self, host: str) -> str:
        token = secrets.token_urlsafe(32)
        with self._lock:
            self._prune()
            self._links[token] = time.time() + self.link_ttl
        return f"https://{host}{LOGIN_PATH}?t={token}"

    def redeem(self, token: str) -> str | None:
        """A fresh session id for an unused, unexpired link; the link then stops working."""
        with self._lock:
            expires = self._links.pop(token, 0)
            if expires < time.time():
                return None
            session = secrets.token_urlsafe(32)
            self._sessions[session] = time.time() + self.session_ttl
            return session

    def valid(self, cookie_header: str) -> bool:
        session = session_from(cookie_header)
        with self._lock:
            return bool(session) and self._sessions.get(session, 0) > time.time()

    def _prune(self) -> None:
        now = time.time()
        for table in (self._links, self._sessions):
            for key in [k for k, t in table.items() if t < now]:
                del table[key]


def session_from(cookie_header: str) -> str:
    for part in (cookie_header or "").split(";"):
        name, _, value = part.strip().partition("=")
        if name == COOKIE:
            return value
    return ""


def without_our_cookie(cookie_header: str) -> str:
    """The Cookie header minus Core's session, so the previewed app never sees it."""
    return "; ".join(p.strip() for p in (cookie_header or "").split(";")
                     if p.strip() and p.strip().partition("=")[0] != COOKIE)
