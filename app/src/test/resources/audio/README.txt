seek-fixture.m4a is a generated 12-second 440 Hz sine wave, not a commercial recording.
Generated with ffmpeg -f lavfi -i sine=frequency=440:duration=12 -c:a aac -b:a 96k -movflags +faststart seek-fixture.m4a
Used to exercise Media3's real MP4 extractor and seek map over HTTP query ranges.

full-180.m4a, full-210.m4a and full-240.m4a are generated 440/550/660 Hz tones,
encoded as AAC at 128 kbps with faststart metadata. They are synthetic test tracks,
not songs fetched from YouTube. They exercise complete multi-minute files, multiple
one-MiB ranges, successive CDN failures, cached seeks and the real MP4 extractor.
