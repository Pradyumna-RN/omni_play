seek-fixture.m4a is a generated 12-second 440 Hz sine wave, not a commercial recording.
Generated with ffmpeg -f lavfi -i sine=frequency=440:duration=12 -c:a aac -b:a 96k -movflags +faststart seek-fixture.m4a
Used to exercise Media3's real MP4 extractor and seek map over HTTP query ranges.
