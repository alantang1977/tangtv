#!/usr/bin/env python3
"""
Batch-resample .cube 3D LUT presets to a target LUT_3D_SIZE.

The app accepts cube sizes up to 65, but large text .cube files are expensive to
parse during interactive playback. This tool converts large LUTs, for example
64-point cubes, to smaller cubes such as 33-point using trilinear sampling.

Usage:
  python3 scripts/resample_cube_luts.py app/src/main/assets/lut_presets --size 33 --output /tmp/luts33
  python3 scripts/resample_cube_luts.py app/src/main/assets/lut_presets --size 33 --suffix ".33"
  python3 scripts/resample_cube_luts.py app/src/main/assets/lut_presets/明亮清新.cube --size 33 --output /tmp/luts33
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


MAX_LUT_SIZE = 65
EPSILON = 0.0001


@dataclass
class Cube:
    path: Path
    size: int
    title: str | None
    values: list[tuple[float, float, float]]


def main() -> int:
    parser = argparse.ArgumentParser(description="Batch-resample .cube 3D LUT files.")
    parser.add_argument("source", type=Path, help="Input .cube file or directory to scan recursively.")
    parser.add_argument("--size", type=int, default=33, help="Target LUT_3D_SIZE. Default: 33.")
    parser.add_argument("--output", type=Path, help="Output file or directory. Defaults to writing beside input with suffix.")
    parser.add_argument("--suffix", default=".33", help="Filename suffix before .cube when --output is omitted or is a directory. Default: .33")
    parser.add_argument("--overwrite", action="store_true", help="Allow overwriting existing output files.")
    parser.add_argument("--include-hidden", action="store_true", help="Include hidden files/directories when scanning a directory.")
    parser.add_argument("--skip-existing-size", action="store_true", help="Skip files that already have the target size.")
    args = parser.parse_args()

    if args.size <= 1 or args.size > MAX_LUT_SIZE:
        parser.error(f"--size must be in 2..{MAX_LUT_SIZE}")

    source = args.source.resolve()
    if not source.exists():
        parser.error(f"source does not exist: {source}")

    files = list(iter_cube_files(source, args.include_hidden))
    if not files:
        print(f"No .cube files found: {source}", file=sys.stderr)
        return 1

    converted = 0
    skipped = 0
    failed = 0
    for path in files:
        try:
            cube = read_cube(path)
            if args.skip_existing_size and cube.size == args.size:
                skipped += 1
                print(f"skip same size: {path}")
                continue
            target = output_path(source, path, args.output, args.suffix)
            if target.exists() and not args.overwrite:
                skipped += 1
                print(f"skip exists: {target}")
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            resized = resample_cube(cube, args.size)
            write_cube(resized, target, cube)
            converted += 1
            print(f"converted: {path} ({cube.size}->{args.size}) -> {target}")
        except Exception as error:
            failed += 1
            print(f"failed: {path}: {error}", file=sys.stderr)

    print(f"done: converted={converted} skipped={skipped} failed={failed}")
    return 1 if failed else 0


def iter_cube_files(source: Path, include_hidden: bool) -> Iterable[Path]:
    if source.is_file():
        if source.suffix.lower() == ".cube":
            yield source
        return
    if not source.is_dir():
        raise ValueError(f"not a file or directory: {source}")
    for root, dirs, files in os.walk(source):
        if not include_hidden:
            dirs[:] = [name for name in dirs if not name.startswith(".")]
        for name in files:
            if not include_hidden and name.startswith("."):
                continue
            path = Path(root) / name
            if path.suffix.lower() == ".cube":
                yield path


def output_path(source_root: Path, input_path: Path, output: Path | None, suffix: str) -> Path:
    if output is None:
        return input_path.with_name(input_path.stem + suffix + input_path.suffix)
    output = output.resolve()
    if source_root.is_file():
        if output.suffix.lower() == ".cube":
            return output
        return output / (input_path.stem + suffix + input_path.suffix)
    return output / input_path.resolve().relative_to(source_root).with_name(input_path.stem + suffix + input_path.suffix)


def read_cube(path: Path) -> Cube:
    title: str | None = None
    size: int | None = None
    expected: int | None = None
    values: list[tuple[float, float, float]] = []
    domain_min = (0.0, 0.0, 0.0)
    domain_max = (1.0, 1.0, 1.0)
    with path.open("r", encoding="utf-8-sig", errors="replace") as file:
        for line_no, raw in enumerate(file, 1):
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            upper = line.upper()
            if upper.startswith("TITLE"):
                title = parse_title(line)
                continue
            if upper.startswith("LUT_1D_SIZE"):
                raise ValueError("1D LUT is not supported")
            if upper.startswith("DOMAIN_MIN"):
                domain_min = parse_tagged_triple(line, line_no, "DOMAIN_MIN")
                continue
            if upper.startswith("DOMAIN_MAX"):
                domain_max = parse_tagged_triple(line, line_no, "DOMAIN_MAX")
                continue
            if upper.startswith("LUT_3D_SIZE"):
                if size is not None:
                    raise ValueError(f"duplicate LUT_3D_SIZE at line {line_no}")
                size = parse_size(line, line_no)
                expected = size * size * size
                continue
            if size is None:
                raise ValueError(f"missing LUT_3D_SIZE before data at line {line_no}")
            if not is_default_domain(domain_min, domain_max):
                raise ValueError("non-default DOMAIN_MIN/MAX is not supported")
            values.append(parse_triple(line, line_no, "LUT data"))
            if expected is not None and len(values) > expected:
                raise ValueError("too many LUT data rows")

    if size is None or expected is None:
        raise ValueError("missing LUT_3D_SIZE")
    if len(values) != expected:
        raise ValueError(f"expected {expected} LUT rows, got {len(values)}")
    return Cube(path=path, size=size, title=title, values=values)


def parse_title(line: str) -> str | None:
    text = line[5:].strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    return text or None


def parse_size(line: str, line_no: int) -> int:
    parts = line.split()
    if len(parts) < 2:
        raise ValueError(f"invalid LUT_3D_SIZE at line {line_no}")
    size = int(parts[1])
    if size <= 1 or size > MAX_LUT_SIZE:
        raise ValueError(f"unsupported LUT size {size}")
    return size


def parse_triple(line: str, line_no: int, name: str) -> tuple[float, float, float]:
    parts = line.split()
    if len(parts) < 3:
        raise ValueError(f"invalid {name} at line {line_no}")
    try:
        return clamp01(float(parts[0])), clamp01(float(parts[1])), clamp01(float(parts[2]))
    except ValueError as error:
        raise ValueError(f"invalid {name} at line {line_no}") from error


def parse_tagged_triple(line: str, line_no: int, name: str) -> tuple[float, float, float]:
    parts = line.split()
    if len(parts) < 4:
        raise ValueError(f"invalid {name} at line {line_no}")
    try:
        return clamp01(float(parts[1])), clamp01(float(parts[2])), clamp01(float(parts[3]))
    except ValueError as error:
        raise ValueError(f"invalid {name} at line {line_no}") from error


def is_default_domain(domain_min: tuple[float, float, float], domain_max: tuple[float, float, float]) -> bool:
    return (
        close(domain_min[0], 0.0)
        and close(domain_min[1], 0.0)
        and close(domain_min[2], 0.0)
        and close(domain_max[0], 1.0)
        and close(domain_max[1], 1.0)
        and close(domain_max[2], 1.0)
    )


def close(value: float, target: float) -> bool:
    return abs(value - target) <= EPSILON


def clamp01(value: float) -> float:
    return min(1.0, max(0.0, value))


def resample_cube(cube: Cube, target_size: int) -> list[tuple[float, float, float]]:
    source_size = cube.size
    if target_size == source_size:
        return list(cube.values)
    result: list[tuple[float, float, float]] = []
    scale = (source_size - 1) / (target_size - 1)
    for b in range(target_size):
        sb = b * scale
        for g in range(target_size):
            sg = g * scale
            for r in range(target_size):
                sr = r * scale
                result.append(sample_trilinear(cube.values, source_size, sr, sg, sb))
    return result


def sample_trilinear(values: list[tuple[float, float, float]], size: int, r: float, g: float, b: float) -> tuple[float, float, float]:
    r0, r1, rt = bounds(r, size)
    g0, g1, gt = bounds(g, size)
    b0, b1, bt = bounds(b, size)

    c000 = sample(values, size, r0, g0, b0)
    c100 = sample(values, size, r1, g0, b0)
    c010 = sample(values, size, r0, g1, b0)
    c110 = sample(values, size, r1, g1, b0)
    c001 = sample(values, size, r0, g0, b1)
    c101 = sample(values, size, r1, g0, b1)
    c011 = sample(values, size, r0, g1, b1)
    c111 = sample(values, size, r1, g1, b1)

    c00 = lerp_color(c000, c100, rt)
    c10 = lerp_color(c010, c110, rt)
    c01 = lerp_color(c001, c101, rt)
    c11 = lerp_color(c011, c111, rt)
    c0 = lerp_color(c00, c10, gt)
    c1 = lerp_color(c01, c11, gt)
    return lerp_color(c0, c1, bt)


def bounds(value: float, size: int) -> tuple[int, int, float]:
    low = int(math.floor(value))
    high = min(size - 1, low + 1)
    low = max(0, min(size - 1, low))
    return low, high, value - low


def sample(values: list[tuple[float, float, float]], size: int, r: int, g: int, b: int) -> tuple[float, float, float]:
    return values[r + size * (g + size * b)]


def lerp_color(a: tuple[float, float, float], b: tuple[float, float, float], t: float) -> tuple[float, float, float]:
    return lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def write_cube(values: list[tuple[float, float, float]], target: Path, source: Cube) -> None:
    size = round(len(values) ** (1 / 3))
    if size * size * size != len(values):
        raise ValueError("internal error: resampled cube is not cubic")
    title = source.title or source.path.stem
    with target.open("w", encoding="utf-8", newline="\n") as file:
        file.write(f'TITLE "{title} ({size})"\n')
        file.write(f"# Resampled from {source.size} to {size} by scripts/resample_cube_luts.py\n")
        file.write(f"# Source: {source.path.name}\n")
        file.write(f"LUT_3D_SIZE {size}\n")
        for red, green, blue in values:
            file.write(f"{red:.6f} {green:.6f} {blue:.6f}\n")


if __name__ == "__main__":
    raise SystemExit(main())
