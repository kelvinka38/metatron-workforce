"""Web tools for the agent: search (Bing or DuckDuckGo result pages, no key, $0) and fetch a page as text.

Only public http(s) addresses: a URL whose host resolves to a private, loopback, link-local or
reserved address is refused at every redirect, so the agent cannot use these tools to reach Core,
Ollama or other containers on the host network.
"""
from __future__ import annotations

import base64
import html
import ipaddress
import os
import re
import socket
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"
MAX_BYTES = 1_500_000
MAX_TEXT = 12_000


class Blocked(ValueError):
    pass


def check_public(url: str) -> None:
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise Blocked("only http(s) URLs")
    try:
        infos = socket.getaddrinfo(parts.hostname, parts.port or (443 if parts.scheme == "https" else 80))
    except socket.gaierror as e:
        raise Blocked(f"cannot resolve {parts.hostname}: {e}") from None
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if not ip.is_global or ip.is_multicast:
            raise Blocked(f"{parts.hostname} is not a public address")


class _CheckRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_public(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_opener = urllib.request.build_opener(_CheckRedirects)


def _get(url: str, timeout: float = 20) -> tuple[str, str]:
    """(final url, decoded body) of a public page."""
    check_public(url)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "vi,en;q=0.8"})
    with _opener.open(req, timeout=timeout) as resp:
        body = resp.read(MAX_BYTES)
        charset = resp.headers.get_content_charset() or "utf-8"
        return resp.geturl(), body.decode(charset, errors="replace")


def page_text(markup: str) -> str:
    markup = re.sub(r"(?is)<(script|style|noscript|svg|head)\b.*?</\1>", " ", markup)
    markup = re.sub(r"(?i)<br\s*/?>|</(p|div|li|h[1-6]|tr|section|article)>", "\n", markup)
    text = html.unescape(re.sub(r"<[^>]+>", " ", markup))
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    return re.sub(r"\n\s*\n+", "\n\n", text).strip()


def fetch_url(url: str) -> str:
    try:
        final, body = _get(url)
    except Blocked as e:
        return f"error: {e}"
    except Exception as e:
        return f"error: could not fetch {url}: {type(e).__name__}: {str(e)[:200]}"
    text = page_text(body) if "<" in body[:2000] else body
    cut = f"\n...[{len(text) - MAX_TEXT} more chars not shown]" if len(text) > MAX_TEXT else ""
    return f"URL: {final}\n\n{text[:MAX_TEXT]}{cut}"


def _real_link(href: str) -> str:
    """The target of a search engine's click-tracking link."""
    href = html.unescape(href)
    if href.startswith("//"):
        href = "https:" + href
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(href).query)
    if "uddg" in q:  # DuckDuckGo
        return q["uddg"][0]
    if "bing.com/ck/" in href and q.get("u", [""])[0].startswith("a1"):  # Bing: "a1" + base64url
        raw = q["u"][0][2:]
        try:
            return base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)).decode()
        except (ValueError, UnicodeDecodeError):
            return href
    return href


def parse_bing(markup: str, limit: int = 8) -> list[dict]:
    out = []
    for block in re.split(r'(?i)<li class="b_algo"', markup)[1:]:
        link = re.search(r'(?is)<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', block)
        if not link:
            continue
        snippet = re.search(r'(?is)<p[^>]*class="[^"]*b_lineclamp[^"]*"[^>]*>(.*?)</p>', block) or \
            re.search(r'(?is)<div class="b_caption"[^>]*>.*?<p[^>]*>(.*?)</p>', block)
        out.append({"title": page_text(link.group(2)), "url": _real_link(link.group(1)),
                    "snippet": page_text(snippet.group(1)) if snippet else ""})
        if len(out) >= limit:
            break
    return out


def parse_duckduckgo(markup: str, limit: int = 8) -> list[dict]:
    out = []
    for block in re.split(r'(?i)<div[^>]+class="[^"]*\bresult\b', markup)[1:]:
        link = re.search(r'(?is)<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>', block)
        if not link:
            continue
        snippet = re.search(r'(?is)class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(a|div|td)>', block)
        url = _real_link(link.group(1))
        if "duckduckgo.com/y.js" in url:  # ads
            continue
        out.append({"title": page_text(link.group(2)), "url": url,
                    "snippet": page_text(snippet.group(1)) if snippet else ""})
        if len(out) >= limit:
            break
    return out


GROUNDING_MODELS = ("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")


def google_search(query: str, key: str, post=None) -> list[dict]:
    """Google Search through Gemini's search grounding, on the free-tier key (no billing account).
    Returns the pages Google used, with Gemini's short answer as the first result's snippet."""
    import json
    post = post or (lambda url, body: json.loads(urllib.request.urlopen(urllib.request.Request(
        url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json",
                                                      "x-goog-api-key": key}), timeout=60).read()))
    body = {"contents": [{"role": "user", "parts": [{"text":
            f"Search the web for: {query}\nList the most relevant facts with numbers and dates, briefly."}]}],
            "tools": [{"google_search": {}}]}
    for model in GROUNDING_MODELS:
        try:
            data = post(f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent", body)
        except Exception:
            continue  # quota, retired model, overload: try the next free model
        cand = (data.get("candidates") or [{}])[0]
        answer = " ".join(p.get("text", "") for p in (cand.get("content") or {}).get("parts", [])).strip()
        chunks = [c["web"] for c in (cand.get("groundingMetadata") or {}).get("groundingChunks", []) if c.get("web")]
        if not chunks:
            continue
        results = [{"title": c.get("title", ""), "url": c.get("uri", ""), "snippet": ""} for c in chunks[:8]]
        results[0]["snippet"] = answer[:1500]
        return results
    return []


ENGINES = [
    ("bing", "https://www.bing.com/search?{}", lambda q: {"q": q, "setlang": "vi", "cc": "VN"}, parse_bing),
    ("duckduckgo", "https://html.duckduckgo.com/html/?{}", lambda q: {"q": q, "kl": "vn-vi"}, parse_duckduckgo),
]


def web_search(query: str) -> str:
    """Top results (title, URL, snippet) from the first free search engine that answers."""
    problems = []
    key = os.environ.get("GEMINI_API_KEY", "")
    if key:
        results = google_search(query, key)
        if results:
            return ("Google (via Gemini search grounding). Summary: " + results[0]["snippet"] + "\n\nSources "
                    "(open them with fetch_url before citing):\n" +
                    "\n".join(f"{i}. {r['title']}\n   {r['url']}" for i, r in enumerate(results, 1)))
        problems.append("google: unavailable")
    for name, url, params, parse in ENGINES:
        results = []
        for _ in range(2):  # result pages are sometimes served empty; one more try usually works
            try:
                _, body = _get(url.format(urllib.parse.urlencode(params(query))))
            except Exception as e:
                problems.append(f"{name}: {type(e).__name__}")
                break
            results = parse(body)
            if results:
                break
        if results:
            return "\n\n".join(f"{i}. {r['title']}\n   {r['url']}\n   {r['snippet']}"
                                for i, r in enumerate(results, 1))
        elif not problems or not problems[-1].startswith(name):
            problems.append(f"{name}: no results")
    return f"no results ({', '.join(problems)}). Try other words, or fetch_url on a site you know."
