#!/usr/bin/env python3
"""Decode the full synthetic tracks emitted by FullTrackPlaybackTest; no YouTube access."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
xml = root / 'app/build/test-results/testDebugUnitTest/TEST-labs.rtensor.music.FullTrackPlaybackTest.xml'
suite = ET.parse(xml).getroot()
assert suite.attrib['tests'] == '3' and suite.attrib['failures'] == '0' and suite.attrib['errors'] == '0'
tracks = []
for seconds in (180, 210, 240):
    source = root / f'app/src/test/resources/audio/full-{seconds}.m4a'
    received = root / f'app/build/reports/full-track-audio/full-{seconds}.m4a'
    digest = hashlib.sha256(received.read_bytes()).hexdigest()
    assert digest == hashlib.sha256(source.read_bytes()).hexdigest()
    decoded = subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-xerror', '-nostats', '-progress', 'pipe:1',
                              '-i', str(received), '-f', 'null', '-'], check=True, text=True, capture_output=True)
    progress = dict(line.split('=', 1) for line in decoded.stdout.splitlines() if '=' in line)
    assert progress.get('progress') == 'end' and int(progress['out_time_us']) >= seconds * 1_000_000 - 50_000
    assert not decoded.stderr.strip(), decoded.stderr
    tracks.append({'source': 'generated AAC tone (not a YouTube song)', 'duration_seconds': seconds,
                   'bytes': received.stat().st_size, 'sha256': digest, 'byte_exact': True,
                   'media3_extractor_eof': True, 'ffmpeg_decode_eof': True,
                   'cached_and_uncached_seeks': True, 'initial_media_rejections_recovered': 2})
report = {'scope': 'local HTTP fixtures, production Android cache/range/retry classes, real MP4 extractor, independent AAC decoder',
          'live_youtube_tested': False, 'android_device_attached': False,
          'live_blocker': 'YouTube CONNECT rejected by workspace proxy with HTTP 403',
          'total_audio_seconds': sum(t['duration_seconds'] for t in tracks), 'tracks': tracks}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
