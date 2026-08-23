#!/usr/bin/env python3

from pathlib import Path
import re
import struct


ROOT = Path(__file__).resolve().parent.parent
OVERRIDE = ROOT / "third_party/mpv-native-overrides/aimagereader-v556/video/out/hwdec"
# Shader/source baseline: FongMi/mpv@fd679c812149fe1f3e246897b1015ae109da7c74,
# which is the Vulkan implementation bundled by v5.5.6-202608072014.
SOURCE = OVERRIDE / "hwdec_aimagereader_vk.c"
SHADER = OVERRIDE / "hwdec_aimagereader.comp"
HEADER = OVERRIDE / "hwdec_aimagereader_comp.h"


def require(pattern: str, text: str, description: str) -> re.Match[str]:
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise SystemExit(f"missing {description}")
    return match


source = SOURCE.read_text(encoding="utf-8")
shader = SHADER.read_text(encoding="utf-8")
header = HEADER.read_text(encoding="utf-8")

shader_group = require(
    r"layout\s*\(\s*local_size_x\s*=\s*(\d+)\s*,\s*local_size_y\s*=\s*(\d+)\s*\)\s*in\s*;",
    shader,
    "v5.5.6 Vulkan shader workgroup declaration",
)
shader_x, shader_y = map(int, shader_group.groups())
dispatch = require(
    r"vkCmdDispatch\([^;]*?MP_ALIGN_UP\(p->width,\s*(\d+)\)\s*/\s*(\d+)\s*,\s*"
    r"MP_ALIGN_UP\(p->height,\s*(\d+)\)\s*/\s*(\d+)\s*,\s*1\s*\);",
    source,
    "v5.5.6 Vulkan dispatch dimensions",
)
dispatch_x_align, dispatch_x_divisor, dispatch_y_align, dispatch_y_divisor = map(
    int, dispatch.groups()
)
if (shader_x, shader_y) != (
    dispatch_x_align,
    dispatch_y_align,
) or (shader_x, shader_y) != (dispatch_x_divisor, dispatch_y_divisor):
    raise SystemExit(
        "v5.5.6 Vulkan dispatch/shader mismatch: "
        f"shader={shader_x}x{shader_y}, "
        f"dispatch=align({dispatch_x_align}x{dispatch_y_align})/"
        f"divide({dispatch_x_divisor}x{dispatch_y_divisor})"
    )

for token in (
    "layout(set = 0, binding = 0) uniform sampler2D source_image;",
    "layout(set = 0, binding = 1) writeonly uniform image2D target_image;",
    "ivec2 size = imageSize(target_image);",
    "imageStore(target_image, position, texture(source_image, uv));",
):
    if token not in shader:
        raise SystemExit(f"v5.5.6 Vulkan shader is missing contract token: {token}")

words = [int(value, 16) for value in re.findall(r"0x[0-9a-fA-F]+", header)]
if len(words) < 5 or words[0] != 0x07230203:
    raise SystemExit("v5.5.6 Vulkan shader header is not valid SPIR-V words")
if words[1] > 0x00010300:
    raise SystemExit(
        "v5.5.6 Vulkan shader exceeds the documented Vulkan 1.1 target: "
        f"SPIR-V word 0x{words[1]:08x}"
    )

offset = 5
local_size = None
descriptor_bindings: dict[int, int] = {}
while offset < len(words):
    instruction = words[offset]
    word_count = instruction >> 16
    opcode = instruction & 0xFFFF
    if word_count == 0 or offset + word_count > len(words):
        raise SystemExit(f"malformed SPIR-V instruction at word {offset}")
    operands = words[offset + 1 : offset + word_count]
    if opcode == 16 and len(operands) >= 5 and operands[1] == 17:
        local_size = tuple(operands[2:5])
    elif opcode == 71 and len(operands) >= 3 and operands[1] == 33:
        descriptor_bindings[operands[0]] = operands[2]
    offset += word_count

if local_size != (shader_x, shader_y, 1):
    raise SystemExit(
        "embedded v5.5.6 SPIR-V workgroup mismatch: "
        f"header={local_size}, shader={shader_x}x{shader_y}x1"
    )
if sorted(descriptor_bindings.values()) != [0, 1]:
    raise SystemExit(
        "embedded v5.5.6 SPIR-V descriptor bindings mismatch: "
        f"{sorted(descriptor_bindings.values())}"
    )

# Pack once to ensure every literal fits a uint32_t exactly.
struct.pack(f"<{len(words)}I", *words)
print(
    "Verified v5.5.6 Vulkan shader contract: "
    f"{shader_x}x{shader_y}, bindings 0/1, {len(words)} SPIR-V words"
)
