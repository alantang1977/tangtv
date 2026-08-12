#!/usr/bin/env python3
"""
Validate WebHTV built-in LUT preset files.

Usage:
  python3 scripts/validate_lut_presets.py /path/to/luts
  python3 scripts/validate_lut_presets.py /path/to/luts --prune
  python3 scripts/validate_lut_presets.py /path/to/luts --copy-to app/src/main/assets/lut_presets

The validation rules intentionally match the App's first LUT implementation:
  - .cube: 3D LUT only, LUT_3D_SIZE 2..65, default DOMAIN_MIN/MAX only.
  - bitmap: .png/.jpg/.jpeg/.webp, Media3 native layout width=N height=N^2, N<=65.
"""

from __future__ import annotations

import argparse
import os
import shutil
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


SUPPORTED_EXTS = {".cube", ".png", ".jpg", ".jpeg", ".webp"}
BITMAP_EXTS = {".png", ".jpg", ".jpeg", ".webp"}
MAX_LUT_SIZE = 65
EPSILON = 0.0001


@dataclass
class ValidationResult:
    path: Path
    ok: bool
    kind: str
    reason: str


def validate_file(path: Path | str) -> ValidationResult:
    path = Path(path)
    ext = path.suffix.lower()
    if ext == ".cube":
        return _validate_cube(path)
    if ext in BITMAP_EXTS:
        return _validate_bitmap(path)
    return ValidationResult(path, False, "unsupported", "unsupported extension")


def scan_presets(source_dir: Path | str, include_hidden: bool = False) -> list[ValidationResult]:
    source = Path(source_dir)
    results: list[ValidationResult] = []
    for path in _iter_files(source, include_hidden):
        results.append(validate_file(path))
    return results


def copy_valid_presets(
    source_dir: Path | str,
    dest_dir: Path | str,
    *,
    include_hidden: bool = False,
    overwrite: bool = False,
    flat: bool = False,
) -> tuple[int, int]:
    source = Path(source_dir).resolve()
    dest = Path(dest_dir).resolve()
    copied = 0
    skipped = 0
    for result in scan_presets(source, include_hidden):
        if not result.ok:
            continue
        target = dest / result.path.name if flat else dest / result.path.resolve().relative_to(source)
        if target.exists() and not overwrite:
            skipped += 1
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        if result.path.resolve() == target.resolve():
            skipped += 1
            continue
        shutil.copy2(result.path, target)
        copied += 1
    return copied, skipped


def prune_invalid(results: Iterable[ValidationResult]) -> int:
    removed = 0
    for result in results:
        if result.ok:
            continue
        try:
            result.path.unlink()
            removed += 1
        except FileNotFoundError:
            pass
    return removed


def _iter_files(source: Path, include_hidden: bool) -> Iterable[Path]:
    if not source.is_dir():
        raise SystemExit(f"not a directory: {source}")
    for root, dirs, files in os.walk(source):
        if not include_hidden:
            dirs[:] = [name for name in dirs if not name.startswith(".")]
        for name in files:
            if not include_hidden and name.startswith("."):
                continue
            yield Path(root) / name


def _validate_cube(path: Path) -> ValidationResult:
    try:
        size = None
        expected = None
        count = 0
        domain_min = (0.0, 0.0, 0.0)
        domain_max = (1.0, 1.0, 1.0)
        with path.open("r", encoding="utf-8-sig", errors="replace") as file:
            for line_no, raw in enumerate(file, 1):
                line = raw.split("#", 1)[0].strip()
                if not line:
                    continue
                upper = line.upper()
                if upper.startswith("TITLE"):
                    continue
                if upper.startswith("LUT_1D_SIZE"):
                    return _bad(path, "cube", "1D LUT is not supported")
                if upper.startswith("DOMAIN_MIN"):
                    domain_min = _parse_triple(line, line_no, "DOMAIN_MIN")
                    continue
                if upper.startswith("DOMAIN_MAX"):
                    domain_max = _parse_triple(line, line_no, "DOMAIN_MAX")
                    continue
                if upper.startswith("LUT_3D_SIZE"):
                    if size is not None:
                        return _bad(path, "cube", f"duplicate LUT_3D_SIZE at line {line_no}")
                    size = _parse_size(line, line_no)
                    expected = size * size * size
                    continue
                if size is None:
                    return _bad(path, "cube", f"missing LUT_3D_SIZE before data at line {line_no}")
                if not _is_default_domain(domain_min, domain_max):
                    return _bad(path, "cube", "non-default DOMAIN_MIN/MAX is not supported")
                _parse_data(line, line_no)
                count += 1
                if expected is not None and count > expected:
                    return _bad(path, "cube", "too many LUT data rows")
        if size is None or expected is None:
            return _bad(path, "cube", "missing LUT_3D_SIZE")
        if not _is_default_domain(domain_min, domain_max):
            return _bad(path, "cube", "non-default DOMAIN_MIN/MAX is not supported")
        if count != expected:
            return _bad(path, "cube", f"expected {expected} LUT rows, got {count}")
        return ValidationResult(path, True, "cube", f"LUT_3D_SIZE {size}")
    except Exception as error:
        return _bad(path, "cube", str(error))


def _parse_size(line: str, line_no: int) -> int:
    parts = line.split()
    if len(parts) < 2:
        raise ValueError(f"invalid LUT_3D_SIZE at line {line_no}")
    size = int(parts[1])
    if size <= 1 or size > MAX_LUT_SIZE:
        raise ValueError(f"unsupported LUT size {size}")
    return size


def _parse_triple(line: str, line_no: int, name: str) -> tuple[float, float, float]:
    parts = line.split()
    if len(parts) < 4:
        raise ValueError(f"invalid {name} at line {line_no}")
    return float(parts[1]), float(parts[2]), float(parts[3])


def _parse_data(line: str, line_no: int) -> None:
    parts = line.split()
    if len(parts) < 3:
        raise ValueError(f"invalid LUT data at line {line_no}")
    float(parts[0])
    float(parts[1])
    float(parts[2])


def _is_default_domain(domain_min: tuple[float, float, float], domain_max: tuple[float, float, float]) -> bool:
    return (
        _close(domain_min[0], 0.0)
        and _close(domain_min[1], 0.0)
        and _close(domain_min[2], 0.0)
        and _close(domain_max[0], 1.0)
        and _close(domain_max[1], 1.0)
        and _close(domain_max[2], 1.0)
    )


def _close(value: float, target: float) -> bool:
    return abs(value - target) <= EPSILON


def _validate_bitmap(path: Path) -> ValidationResult:
    try:
        width, height = _image_size(path)
        if width <= 1 or width > MAX_LUT_SIZE or height != width * width:
            return _bad(path, "bitmap", f"bitmap LUT must be N x N^2, got {width}x{height}")
        return ValidationResult(path, True, "bitmap", f"{width}x{height}")
    except Exception as error:
        return _bad(path, "bitmap", str(error))


def _image_size(path: Path) -> tuple[int, int]:
    ext = path.suffix.lower()
    with path.open("rb") as file:
        if ext == ".png":
            return _png_size(file.read(32))
        if ext in {".jpg", ".jpeg"}:
            return _jpeg_size(file)
        if ext == ".webp":
            return _webp_size(file)
    raise ValueError("unsupported image type")


def _png_size(header: bytes) -> tuple[int, int]:
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("invalid PNG")
    return struct.unpack(">II", header[16:24])


def _jpeg_size(file) -> tuple[int, int]:
    if file.read(2) != b"\xff\xd8":
        raise ValueError("invalid JPEG")
    sof_markers = set(range(0xC0, 0xC4)) | set(range(0xC5, 0xC8)) | set(range(0xC9, 0xCC)) | set(range(0xCD, 0xD0))
    while True:
        byte = file.read(1)
        if not byte:
            break
        if byte != b"\xff":
            continue
        marker = file.read(1)
        while marker == b"\xff":
            marker = file.read(1)
        if not marker:
            break
        code = marker[0]
        if code in {0xD8, 0xD9} or 0xD0 <= code <= 0xD7:
            continue
        length_bytes = file.read(2)
        if len(length_bytes) != 2:
            break
        length = struct.unpack(">H", length_bytes)[0]
        if length < 2:
            raise ValueError("invalid JPEG segment")
        if code in sof_markers:
            data = file.read(length - 2)
            if len(data) < 5:
                break
            height = struct.unpack(">H", data[1:3])[0]
            width = struct.unpack(">H", data[3:5])[0]
            return width, height
        file.seek(length - 2, os.SEEK_CUR)
    raise ValueError("JPEG size not found")


def _webp_size(file) -> tuple[int, int]:
    header = file.read(12)
    if len(header) != 12 or header[:4] != b"RIFF" or header[8:12] != b"WEBP":
        raise ValueError("invalid WebP")
    while True:
        chunk_header = file.read(8)
        if len(chunk_header) != 8:
            break
        chunk_id = chunk_header[:4]
        chunk_size = struct.unpack("<I", chunk_header[4:])[0]
        data = file.read(chunk_size)
        if chunk_size % 2 == 1:
            file.read(1)
        if chunk_id == b"VP8X" and len(data) >= 10:
            width = 1 + int.from_bytes(data[4:7], "little")
            height = 1 + int.from_bytes(data[7:10], "little")
            return width, height
        if chunk_id == b"VP8L" and len(data) >= 5 and data[0] == 0x2F:
            b0, b1, b2, b3 = data[1], data[2], data[3], data[4]
            width = 1 + (((b1 & 0x3F) << 8) | b0)
            height = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6))
            return width, height
        if chunk_id == b"VP8 " and len(data) >= 10 and data[3:6] == b"\x9d\x01\x2a":
            width = struct.unpack("<H", data[6:8])[0] & 0x3FFF
            height = struct.unpack("<H", data[8:10])[0] & 0x3FFF
            return width, height
    raise ValueError("WebP size not found")


def _bad(path: Path, kind: str, reason: str) -> ValidationResult:
    return ValidationResult(path, False, kind, reason)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Validate WebHTV LUT preset files.")
    parser.add_argument("source", type=Path, help="source directory to scan recursively")
    parser.add_argument("--prune", action="store_true", help="delete invalid files from source")
    parser.add_argument("--copy-to", type=Path, help="copy valid presets to this directory")
    parser.add_argument("--overwrite", action="store_true", help="overwrite existing files when copying")
    parser.add_argument("--flat", action="store_true", help="copy valid presets without preserving subdirectories")
    parser.add_argument("--include-hidden", action="store_true", help="also scan hidden files and directories")
    args = parser.parse_args(argv)

    results = scan_presets(args.source, args.include_hidden)
    valid = [item for item in results if item.ok]
    invalid = [item for item in results if not item.ok]

    for item in results:
        status = "OK" if item.ok else "BAD"
        print(f"{status}\t{item.kind}\t{item.path}\t{item.reason}")

    if args.copy_to:
        copied, skipped = copy_valid_presets(
            args.source,
            args.copy_to,
            include_hidden=args.include_hidden,
            overwrite=args.overwrite,
            flat=args.flat,
        )
        print(f"copied={copied} skipped={skipped} dest={args.copy_to}")

    removed = 0
    if args.prune:
        removed = prune_invalid(invalid)
        print(f"removed={removed}")

    print(f"summary: valid={len(valid)} invalid={len(invalid)} total={len(results)}")
    return 0 if not invalid else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
