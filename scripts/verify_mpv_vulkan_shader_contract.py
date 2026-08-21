#!/usr/bin/env python3

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "third_party/mpv-native-overrides/aimagereader-stable/video/out/hwdec/hwdec_aimagereader_vk_stable.c"
SHADER = ROOT / "third_party/mpv-native-overrides/aimagereader-stable/video/out/hwdec/hwdec_aimagereader_vk_stable.comp"
STALE_HEADER = ROOT / "third_party/mpv-native-overrides/aimagereader-stable/video/out/hwdec/hwdec_aimagereader_vk_stable_comp.h"


def require(pattern: str, text: str, description: str) -> re.Match[str]:
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise SystemExit(f"missing {description}")
    return match


source = SOURCE.read_text(encoding="utf-8")
shader = SHADER.read_text(encoding="utf-8")

shader_group = require(
    r"layout\s*\(\s*local_size_x\s*=\s*(\d+)\s*,\s*local_size_y\s*=\s*(\d+)\s*\)\s*in\s*;",
    shader,
    "stable Vulkan shader workgroup declaration",
)
source_x = int(require(r"^#define\s+STABLE_WORKGROUP_X\s+(\d+)\s*$", source, "STABLE_WORKGROUP_X").group(1))
source_y = int(require(r"^#define\s+STABLE_WORKGROUP_Y\s+(\d+)\s*$", source, "STABLE_WORKGROUP_Y").group(1))
shader_x, shader_y = map(int, shader_group.groups())
if (source_x, source_y) != (shader_x, shader_y):
    raise SystemExit(
        "stable Vulkan dispatch/shader mismatch: "
        f"C={source_x}x{source_y}, shader={shader_x}x{shader_y}"
    )
if source_x * source_y > 128:
    raise SystemExit(
        "stable Vulkan shader exceeds the 128-invocation Vulkan core baseline: "
        f"{source_x}x{source_y}"
    )

for token in (
    "vec2 uv_offset;",
    "vec2 uv_scale;",
    "ivec2 output_size;",
    "params.uv_offset",
    "params.uv_scale",
):
    if token not in shader:
        raise SystemExit(f"stable Vulkan shader is missing optimized coordinate token: {token}")
for token in (
    "float uv_offset[2];",
    "float uv_scale[2];",
    "int32_t output_size[2];",
    "aimagereader_vk_stable_comp.inc",
    "CPU-precomputed UV transform",
):
    if token not in source:
        raise SystemExit(f"stable Vulkan source is missing shader contract token: {token}")
if STALE_HEADER.exists():
    raise SystemExit(f"unused stale shader header must not exist: {STALE_HEADER}")

print(f"Verified stable Vulkan shader contract: {source_x}x{source_y}, CPU-precomputed UV transform")
