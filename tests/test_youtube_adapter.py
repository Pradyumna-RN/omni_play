import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

path = Path(__file__).resolve().parents[1] / "app/src/main/python/youtube_adapter.py"
spec = importlib.util.spec_from_file_location("youtube_adapter", path)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class FakeYDL:
    responses = []
    calls = []

    def __init__(self, options):
        self.options = options

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def extract_info(self, target, download):
        self.calls.append((target, download, self.options))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


class YouTubeAdapterTest(unittest.TestCase):
    def setUp(self):
        FakeYDL.responses = []
        FakeYDL.calls = []

    def test_rejects_urls_outside_youtube_before_network(self):
        for link in ["https://youtube.com.evil.test/watch?v=jNQXAC9IVRw", "file:///etc/passwd", "https://user@youtube.com/watch?v=jNQXAC9IVRw"]:
            self.assertIsNone(adapter.normalize_link(link))
            self.assertIn("error", json.loads(adapter.resolve(link)))

    def test_search_preserves_provider_order_and_ids(self):
        FakeYDL.responses = [{"_type": "playlist", "entries": [
            {"id": "aaaaaaaaaaa", "title": "Zebra", "uploader": "One"},
            {"id": "bbbbbbbbbbb", "title": "Apple", "uploader": "Two"},
        ]}]
        with patch("yt_dlp.YoutubeDL", FakeYDL):
            result = json.loads(adapter.search("ambient music"))
        self.assertEqual(["yt:aaaaaaaaaaa", "yt:bbbbbbbbbbb"], [t["id"] for t in result["tracks"]])
        self.assertEqual("ytsearch20:ambient music", FakeYDL.calls[0][0])
        self.assertFalse(FakeYDL.calls[0][1])

    def test_resolution_passes_required_headers_and_uses_no_downloads(self):
        FakeYDL.responses = [{"url": "https://r1.googlevideo.com/videoplayback?token=ephemeral", "ext": "m4a", "http_headers": {"User-Agent": "A client", "Referer": "https://www.youtube.com/", "Cookie": "private"}}]
        with patch("yt_dlp.YoutubeDL", FakeYDL):
            result = json.loads(adapter.resolve("https://youtu.be/jNQXAC9IVRw"))
        self.assertEqual("audio/mp4", result["mimeType"])
        self.assertEqual("A client", result["headers"]["User-Agent"])
        self.assertNotIn("Cookie", result["headers"])
        self.assertFalse(FakeYDL.calls[0][1])

    def test_insecure_streams_are_rejected_and_errors_hide_tokens(self):
        FakeYDL.responses = [{"url": "http://r1.googlevideo.com/videoplayback?secret=123"}, Exception("Failed https://r1.googlevideo.com/?secret=456")]
        with patch("yt_dlp.YoutubeDL", FakeYDL):
            result = adapter.resolve("https://youtu.be/jNQXAC9IVRw")
        self.assertIn("error", json.loads(result))
        self.assertNotIn("secret", result)

    def test_client_fallback_is_bounded(self):
        FakeYDL.responses = [Exception("Client unavailable"), {"url": "https://r1.googlevideo.com/audio", "ext": "webm"}]
        with patch("yt_dlp.YoutubeDL", FakeYDL):
            result = json.loads(adapter.resolve("https://youtu.be/jNQXAC9IVRw"))
        self.assertEqual("audio/webm", result["mimeType"])
        self.assertEqual(2, len(FakeYDL.calls))


if __name__ == "__main__":
    unittest.main()
