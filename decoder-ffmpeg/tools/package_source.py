#!/usr/bin/env python3
"""Package complete LGPL source and public adapter; never include private DSP."""
import argparse, hashlib, pathlib, tarfile
from build import SHA256, ROOT
p = argparse.ArgumentParser()
p.add_argument("--archive", type=pathlib.Path, required=True)
p.add_argument("--output", type=pathlib.Path, required=True)
a = p.parse_args()
assert hashlib.sha256(a.archive.read_bytes()).hexdigest() == SHA256, "Wrong FFmpeg source archive"
def public(info):
    parts = pathlib.PurePosixPath(info.name).parts
    return None if any(x in {"build", ".gradle", "__pycache__", ".git"} for x in parts) or info.name.endswith((".so", ".pyc")) else info
with tarfile.open(a.output, "w:gz") as bundle:
    bundle.add(a.archive, arcname="ffmpeg-n6.1.6.tar.gz")
    bundle.add(ROOT, arcname="decoder-ffmpeg", filter=public)
print(a.output)
