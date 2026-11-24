"""Fetch selected original Unreal dependencies from a pinned GitDependencies manifest."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


class DependencyError(RuntimeError):
    pass


def download(url: str, destination: Path, proxy: str, seconds: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    offset = destination.stat().st_size if destination.exists() else 0
    headers = {"User-Agent": "LumenFX-DependencyImporter/1"}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"https": proxy, "http": proxy}))
    started = time.monotonic()
    with opener.open(urllib.request.Request(url, headers=headers), timeout=20) as response:
        resumed = response.status == 206
        mode = "ab" if offset and resumed else "wb"
        if resumed and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
            raise DependencyError("Server returned an inconsistent partial range")
        written = offset if resumed else 0
        with destination.open(mode) as output:
            while chunk := response.read(1024 * 256):
                output.write(chunk)
                written += len(chunk)
                if time.monotonic() - started >= seconds:
                    print(json.dumps({"file": str(destination), "bytes": written, "complete": False}), flush=True)
                    return
    print(json.dumps({"file": str(destination), "bytes": written, "complete": True}), flush=True)


def materialize(manifest: Path, names: list[str], output: Path, cache: Path, proxy: str) -> None:
    root = ET.parse(manifest).getroot()
    sections = {}
    for name in ("Files", "Blobs", "Packs"):
        section = root.find(name)
        if section is None:
            raise DependencyError(f"Missing manifest section: {name}")
        sections[name] = section
    files = {entry.attrib["Name"]: entry.attrib for entry in sections["Files"]}
    blobs = {entry.attrib["Hash"]: entry.attrib for entry in sections["Blobs"]}
    packs = {entry.attrib["Hash"]: entry.attrib for entry in sections["Packs"]}
    selected: dict[str, list[tuple[str, dict[str, str]]]] = {}
    for name in names:
        if name not in files:
            raise DependencyError(f"Dependency not present in manifest: {name}")
        blob = blobs[files[name]["Hash"]]
        selected.setdefault(blob["PackHash"], []).append((name, blob))
    base = root.attrib["BaseUrl"].rstrip("/")
    records: list[dict[str, object]] = []
    for pack_hash, members in selected.items():
        pack = packs[pack_hash]
        packed = cache / "packs" / (pack_hash + ".gz")
        size = int(pack["CompressedSize"])
        url = f"{base}/{pack['RemotePath']}/{pack_hash}"
        if not packed.exists() or packed.stat().st_size != size:
            download(url, packed, proxy, 35)
        if packed.stat().st_size != size:
            raise DependencyError(f"Incomplete dependency pack {pack_hash}: {packed.stat().st_size}/{size}")
        with gzip.open(packed, "rb") as stream:
            magic = stream.read(8)
            if magic != b"UEPACK00":
                raise DependencyError(f"Unknown dependency pack magic: {magic!r}")
            position = 8
            for name, blob in sorted(members, key=lambda value: int(value[1]["PackOffset"])):
                target = int(blob["PackOffset"])
                while position < target:
                    skipped = stream.read(min(target - position, 1024 * 256))
                    if not skipped:
                        raise DependencyError(f"Truncated pack before {name}")
                    position += len(skipped)
                data = stream.read(int(blob["Size"]))
                position += len(data)
                if len(data) != int(blob["Size"]) or hashlib.sha1(data).hexdigest() != blob["Hash"]:
                    raise DependencyError(f"Dependency payload does not match manifest: {name}")
                destination = (output / name).resolve()
                if not destination.is_relative_to(output.resolve()):
                    raise DependencyError(f"Dependency escapes output root: {name}")
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                records.append({"path": str(destination), "sha1": blob["Hash"], "bytes": len(data), "pack": url})
    (output / "dependency-provenance.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf8")
    print(json.dumps(records, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy", default="http://127.0.0.1:7890")
    commands = parser.add_subparsers(dest="command", required=True)
    fetch = commands.add_parser("manifest")
    fetch.add_argument("url")
    fetch.add_argument("destination", type=Path)
    fetch.add_argument("--seconds", type=int, default=35)
    extract = commands.add_parser("assets")
    extract.add_argument("manifest", type=Path)
    extract.add_argument("output", type=Path)
    extract.add_argument("cache", type=Path)
    extract.add_argument("names", nargs="+")
    args = parser.parse_args()
    if args.command == "manifest":
        download(args.url, args.destination, args.proxy, args.seconds)
    else:
        materialize(args.manifest, args.names, args.output, args.cache, args.proxy)


if __name__ == "__main__":
    main()
