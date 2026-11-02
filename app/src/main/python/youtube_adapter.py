"""YouTube metadata and stream resolution; playback stays in Android Media3.

No cookies, account passwords, API keys, downloads, or persistent stream URLs.
This module uses yt-dlp's public API, not code from the private DSP repository.
"""
import json
import re
from urllib.parse import parse_qs, urlsplit

USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
_VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}\Z")
_PLAYLIST_ID = re.compile(r"[A-Za-z0-9_-]{10,100}\Z")
_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "www.youtube-nocookie.com"}


class QuietLogger:
    def debug(self, message):
        pass

    def warning(self, message):
        pass

    def error(self, message):
        pass


def normalize_link(text):
    text = text.strip()
    if _VIDEO_ID.fullmatch(text):
        return f"https://www.youtube.com/watch?v={text}"
    try:
        parsed = urlsplit(text)
        if parsed.scheme not in ("https", "http") or parsed.username or parsed.port:
            return None
    except ValueError:
        return None
    host = parsed.hostname
    parts = parsed.path.strip("/").split("/")
    query = parse_qs(parsed.query)
    video = None
    if host in ("youtu.be", "www.youtu.be") and len(parts) == 1:
        video = parts[0]
    elif host in _HOSTS:
        if parsed.path == "/watch":
            video = query.get("v", [None])[0]
        elif len(parts) == 2 and parts[0] in ("shorts", "embed", "live"):
            video = parts[1]
    else:
        return None
    if video and _VIDEO_ID.fullmatch(video):
        return f"https://www.youtube.com/watch?v={video}"
    playlist = query.get("list", [None])[0]
    if host in _HOSTS and parsed.path == "/playlist" and playlist and _PLAYLIST_ID.fullmatch(playlist):
        return f"https://www.youtube.com/playlist?list={playlist}"
    return None


def _options():
    return {
        "quiet": True, "no_warnings": True, "logger": QuietLogger(),
        "skip_download": True, "cachedir": False,
        "socket_timeout": 18, "retries": 1, "extractor_retries": 1,
        "http_headers": {"User-Agent": USER_AGENT, "Referer": "https://www.youtube.com/"},
    }


def _track(info):
    video = str(info.get("id") or "")
    if not _VIDEO_ID.fullmatch(video):
        return None
    thumbs = info.get("thumbnails") or []
    artwork = info.get("thumbnail") or (thumbs[-1].get("url", "") if thumbs else "")
    if not artwork.startswith("https://"):
        artwork = f"https://i.ytimg.com/vi/{video}/hqdefault.jpg"
    return {
        "id": f"yt:{video}", "title": info.get("title") or "Untitled recording",
        "artist": info.get("artist") or info.get("uploader") or info.get("channel") or "YouTube",
        "url": f"https://www.youtube.com/watch?v={video}", "artwork": artwork,
        "durationMs": int((info.get("duration") or 0) * 1000), "source": "YOUTUBE",
    }


def _error(exc):
    text = str(exc).lower()
    if any(s in text for s in ("sign in", "confirm you're", "bot", "429", "captcha")):
        return "YouTube is asking for verification. Try again later or use a local audio file."
    if any(s in text for s in ("private video", "unavailable", "removed", "not available")):
        return "This recording is unavailable. Try another YouTube link."
    if any(s in text for s in ("timed out", "network", "connection", "resolve")):
        return "Could not reach YouTube. Check your connection and retry."
    return "YouTube did not provide playable audio. Retry or choose another recording."


def search(query):
    from yt_dlp import YoutubeDL
    query = query.strip()[:400]
    if not query:
        return json.dumps({"tracks": []})
    canonical = normalize_link(query)
    if "://" in query and not canonical:
        return json.dumps({"error": "Use a YouTube video or playlist link."})
    opts = _options()
    opts.update({"extract_flat": "in_playlist", "playlistend": 25, "noplaylist": True})
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(canonical or f"ytsearch20:{query}", download=False)
        entries = info.get("entries") if info.get("_type") in ("playlist", "multi_video") else [info]
        # Preserve the provider's returned order. Never rank or blend results.
        tracks = [track for entry in entries or [] if entry and (track := _track(entry))]
        return json.dumps({"tracks": tracks})
    except Exception as exc:
        return json.dumps({"error": _error(exc)})


def resolve(url):
    from yt_dlp import YoutubeDL
    canonical = normalize_link(url)
    if not canonical or "watch?v=" not in canonical:
        return json.dumps({"error": "Choose a YouTube recording to play."})
    opts = _options()
    opts.update({"format": "bestaudio[ext=m4a]/bestaudio", "noplaylist": True})
    # Android VR can expose direct audio streams without a desktop JS runtime.
    # The normal client remains a fallback as provider behaviour changes.
    last_error = None
    for clients in (["android_vr"], None):
        attempt = dict(opts)
        if clients:
            attempt["extractor_args"] = {"youtube": {"player_client": clients}}
        try:
            with YoutubeDL(attempt) as ydl:
                info = ydl.extract_info(canonical, download=False)
            if info.get("is_live") or "m3u8" in str(info.get("protocol", "")):
                return json.dumps({"error": "Live streams aren’t supported in this build. Choose a recording."})
            stream = info.get("url") or ""
            if urlsplit(stream).scheme != "https":
                raise ValueError("No secure audio stream")
            # These headers apply only to this stream, in memory, in the media service.
            allowed = {"user-agent", "referer", "origin", "accept", "accept-language", "range"}
            headers = {k: v for k, v in (info.get("http_headers") or {}).items() if k.lower() in allowed}
            ext = info.get("ext", "")
            mime = {"m4a": "audio/mp4", "webm": "audio/webm", "mp3": "audio/mpeg"}.get(ext)
            return json.dumps({"url": stream, "mimeType": mime, "headers": headers})
        except Exception as exc:
            last_error = exc
    return json.dumps({"error": _error(last_error)})
