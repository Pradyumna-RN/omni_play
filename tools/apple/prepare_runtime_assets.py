"""Prepare a user-authorized, pinned APKM for an external runtime-bundled build.

Does not download installers, execute vendor code or handle account credentials.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
NOTICE_SHA = "e9f0f1ec4f861f5d36f845a6201e76f9e5f2b695a06aa39740612e3698576c60"


def prepare(archive: Path, output: Path):
    output = output.resolve()
    if output.is_relative_to(ROOT) or output.exists():
        raise ValueError("Use a new output directory outside the public source repository")
    output.parent.mkdir(parents=True, exist_ok=True)
    profile = json.loads((ROOT / "app/src/main/assets/apple/runtime-profile.json").read_text())
    pending = Path(tempfile.mkdtemp(prefix="runtime-assets-", dir=output.parent))
    try:
        with zipfile.ZipFile(archive) as package:
            def nested(name):
                entry = package.getinfo(name)
                if entry.file_size > 128 * 1024 * 1024:
                    raise ValueError("Installer split exceeds the size limit")
                return zipfile.ZipFile(io.BytesIO(package.read(entry)))
            for abi, libraries in profile["libs"].items():
                directory = pending / "apple-runtime" / abi
                directory.mkdir(parents=True)
                with nested("split_config." + abi.replace("-", "_") + ".apk") as apk:
                    for name, expected in libraries.items():
                        entry = apk.getinfo(f"lib/{abi}/{name}")
                        if entry.file_size > 64 * 1024 * 1024:
                            raise ValueError("Runtime image exceeds the size limit")
                        data = apk.read(entry)
                        if hashlib.sha256(data).hexdigest() != expected:
                            raise ValueError(f"Unsupported runtime bytes: {abi}/{name}")
                        # A .so asset is stored uncompressed by Android packaging.
                        # Restore its original name only during private on-device setup.
                        (directory / (name + ".bin")).write_bytes(data)
            with nested("base.apk") as base:
                entry = base.getinfo("assets/acknowledgements.html")
                if entry.file_size > 1024 * 1024:
                    raise ValueError("Acknowledgements exceed the size limit")
                notice = base.read(entry)
                if hashlib.sha256(notice).hexdigest() != NOTICE_SHA:
                    raise ValueError("The original runtime acknowledgements do not match")
                (pending / "apple-runtime/acknowledgements.html").write_bytes(notice)
        pending.rename(output)
    finally:
        if pending.exists():
            shutil.rmtree(pending)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("installer", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    prepare(args.installer, args.output)
    print("Verified runtime assets prepared outside the source repository")
