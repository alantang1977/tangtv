#!/usr/bin/env python3
import colorsys
import math
import os
import random
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


SIZES = {
    "mobile": (1080, 2400),
    "leanback": (1920, 1080),
}


def template(name, light, high, top, center, bottom, ops):
    return {
        "name": name,
        "light": light,
        "high": high,
        "top": top,
        "center": center,
        "bottom": bottom,
        "ops": ops,
    }


TEMPLATES = [
    template("aurora_glass", False, False, 0xFF0B2737, 0xFF2B8ECB, 0xFF8B6FE8, [
        ("glow", 0.12, 0.20, 0.80, 0xAA46D6FF), ("glow", 0.86, 0.18, 0.76, 0x8A5B79FF), ("glow", 0.54, 0.86, 0.82, 0x884ED9B7),
        ("flow", 0x66CFFBFF, 0.16, 0.40, 0.54, 0.08), ("flow", 0x4D9E7DFF, 0.46, 0.68, 0.42, -0.16), ("haze", 0x33FFFFFF, 0.18, 0.58, 1.06),
    ]),
    template("sunset_prism", False, False, 0xFF24152F, 0xFFFF6C8E, 0xFF5B67D6, [
        ("glow", 0.18, 0.22, 0.78, 0xB5FF8E72), ("glow", 0.82, 0.52, 0.76, 0x96FFD466), ("glow", 0.60, 0.12, 0.58, 0x805C7CFA),
        ("flow", 0x70FFE7B1, 0.30, 0.56, 0.50, -0.12), ("flow", 0x4DFF72B9, 0.58, 0.82, 0.42, 0.20), ("haze", 0x28FFFFFF, 0.08, 0.40, 0.96),
    ]),
    template("mint_glacier", False, False, 0xFF12323D, 0xFF55D8BE, 0xFF6C82F1, [
        ("glow", 0.76, 0.18, 0.76, 0xAA90FFE5), ("glow", 0.18, 0.72, 0.84, 0x7747B8FF), ("glow", 0.46, 0.48, 0.54, 0x66E7FFF6),
        ("flow", 0x6695FFF1, 0.16, 0.32, 0.34, 0.18), ("flow", 0x446BA8FF, 0.54, 0.82, 0.48, -0.08), ("haze", 0x2CFFFFFF, 0.24, 0.58, 0.88),
    ]),
    template("liquid_chrome", False, False, 0xFF121826, 0xFF53657F, 0xFF27304C, [
        ("glow", 0.28, 0.18, 0.74, 0x7CA8D4FF), ("glow", 0.86, 0.68, 0.90, 0x88DDB3FF), ("glow", 0.34, 0.82, 0.76, 0x55FFF7EC),
        ("current", 0x68FFFFFF, 0.22, -0.18), ("current", 0x4EA7D7FF, 0.50, 0.08), ("current", 0x3DF2E9FF, 0.72, -0.04), ("haze", 0x22FFFFFF, 0.02, 0.36, 1.00),
    ]),
    template("neon_berry", False, False, 0xFF201337, 0xFF7B42CF, 0xFF0F4660, [
        ("glow", 0.18, 0.20, 0.76, 0xB0FF4FCB), ("glow", 0.82, 0.28, 0.68, 0x8A6BDBFF), ("glow", 0.50, 0.84, 0.86, 0x88B76BFF),
        ("flow", 0x66FF7BD7, 0.24, 0.54, 0.46, 0.16), ("flow", 0x5269D8FF, 0.48, 0.76, 0.48, -0.18), ("haze", 0x26FFFFFF, 0.22, 0.50, 1.10),
    ]),
    template("champagne_mist", False, False, 0xFF3B2B36, 0xFFC07A9F, 0xFF7E6548, [
        ("glow", 0.18, 0.18, 0.86, 0x8EFFD8A8), ("glow", 0.84, 0.52, 0.76, 0x7AF5A1C7), ("glow", 0.46, 0.86, 0.80, 0x68FFE9D1),
        ("flow", 0x5CFFE8C8, 0.22, 0.44, 0.38, -0.12), ("flow", 0x42FFC7E1, 0.50, 0.78, 0.42, 0.18), ("haze", 0x30FFFFFF, 0.16, 0.46, 0.94),
    ]),
    template("glass_gradient", True, True, 0xFF335F78, 0xFF74689D, 0xFF8A6974, [
        ("glow", 0.05, 0.20, 0.95, 0x9D93F8FF), ("glow", 0.88, 0.14, 0.76, 0x84EFC0FF), ("glow", 0.22, 0.82, 0.86, 0x70A7FFE6),
        ("flow", 0x5CFFFFFF, 0.12, 0.42, 0.40, -0.08), ("flow", 0x38D2B4FF, 0.58, 0.86, 0.36, 0.12),
        ("arc", -0.14, 0.70, 0.68, 0x70FFFFFF, 0.010, 0.014, 312, 92), ("bubble", 0.90, 0.18, 0.16, 0x32FFFFFF, 0x80FFFFFF), ("bubble", 0.28, 0.78, 0.23, 0x24FFFFFF, 0x55FFFFFF), ("haze", 0x20FFFFFF, 0.46, 0.46, 1.08),
    ]),
    template("deep_space_glass", False, False, 0xFF030817, 0xFF101A41, 0xFF070813, [
        ("stars", 120), ("glow", 0.88, 0.10, 0.54, 0x88D61574), ("glow", 0.88, 0.78, 0.76, 0x7A116CFF), ("glow", 0.26, 0.82, 0.66, 0x553F2BFF),
        ("beam", 0x4E7C42FF, 0.48, -0.08), ("beam", 0x38E74DFF, 0.58, 0.06), ("arc", 0.66, 1.04, 0.64, 0x665F88FF, 0.010, 0.016, 206, 112), ("arc", 1.12, 0.86, 0.42, 0x52B764FF, 0.008, 0.018, 172, 82), ("haze", 0x18FFFFFF, 0.44, 0.48, 1.04),
    ]),
    template("polar_light_glass", True, True, 0xFF365E74, 0xFF626C9B, 0xFF3F8B84, [
        ("glow", 0.26, 0.16, 0.82, 0xAFFFFFFF), ("glow", 0.82, 0.24, 0.78, 0x80B5E6FF), ("glow", 0.72, 0.80, 0.82, 0x73B9FFF2),
        ("flow", 0x48FFFFFF, 0.20, 0.48, 0.42, -0.10), ("flow", 0x32CBB8FF, 0.52, 0.82, 0.40, 0.12), ("bubble", 0.82, 0.16, 0.26, 0x2CFFFFFF, 0x84FFFFFF), ("bubble", 0.04, 0.98, 0.28, 0x20FFFFFF, 0x45FFFFFF), ("haze", 0x22FFFFFF, 0.48, 0.50, 1.10),
    ]),
    template("neon_cyber", False, False, 0xFF020510, 0xFF101645, 0xFF060613, [
        ("stars", 60), ("glow", 0.98, 0.56, 0.70, 0x903F10D8), ("glow", 0.86, 0.82, 0.54, 0x8AFF3DE0), ("glow", 0.14, 0.28, 0.58, 0x5D00C8FF),
        ("beam", 0x6B1DCBFF, 0.36, -0.06), ("beam", 0x65E84DFF, 0.55, 0.10), ("current", 0x633BE4FF, 0.76, -0.08), ("arc", 1.08, 0.90, 0.48, 0x75F04DFF, 0.011, 0.018, 190, 104), ("haze", 0x16FFFFFF, 0.42, 0.52, 1.02),
    ]),
    template("warm_moon_glass", True, False, 0xFF6A5352, 0xFF926C60, 0xFF725C82, [
        ("glow", 0.80, 0.16, 0.64, 0x78FFE7C8), ("glow", 0.16, 0.82, 0.86, 0x72C9E6FF), ("glow", 0.72, 0.70, 0.76, 0x65FFB1CF),
        ("bubble", 0.76, 0.22, 0.23, 0x24FFFFFF, 0x55FFFFFF), ("bubble", 0.22, 0.78, 0.20, 0x20FFFFFF, 0x44FFFFFF), ("flow", 0x42FFFFFF, 0.30, 0.62, 0.42, 0.08), ("arc", 0.82, 1.02, 0.56, 0x54FFD6A6, 0.010, 0.016, 216, 96), ("haze", 0x28FFFFFF, 0.40, 0.44, 1.08),
    ]),
    template("crystal_sky", True, True, 0xFF4B6388, 0xFF6877AE, 0xFF877DA3, [
        ("glow", 0.18, 0.20, 0.82, 0x8DC4FFFF), ("glow", 0.92, 0.28, 0.76, 0x73BCA4FF), ("glow", 0.60, 0.82, 0.80, 0x5CFFE4C8),
        ("bubble", 0.88, 0.13, 0.17, 0x2AFFFFFF, 0x8EFFFFFF), ("bubble", 1.08, 0.62, 0.25, 0x24BFD2FF, 0x68FFFFFF), ("arc", 0.96, 0.18, 0.23, 0x72D2C2FF, 0.010, 0.014, 122, 250), ("arc", 0.92, 0.62, 0.20, 0x55A6B7FF, 0.010, 0.016, 128, 160), ("flow", 0x35FFFFFF, 0.40, 0.70, 0.38, -0.06), ("haze", 0x24FFFFFF, 0.46, 0.44, 1.06),
    ]),
    template("dream_purple", True, False, 0xFF6252B9, 0xFF8B70B5, 0xFF405BAC, [
        ("glow", 0.18, 0.16, 0.78, 0x77FFFFFF), ("glow", 0.80, 0.78, 0.78, 0x88FF9CCB), ("glow", 0.30, 0.70, 0.64, 0x60F4C4FF),
        ("flow", 0x54FFFFFF, 0.18, 0.46, 0.38, -0.08), ("flow", 0x48FFB6DF, 0.48, 0.76, 0.42, 0.12), ("bubble", 0.72, 0.72, 0.16, 0x1CFFFFFF, 0x5FFFFFFF), ("haze", 0x20FFFFFF, 0.34, 0.42, 1.08),
    ]),
    template("sky_mint", True, True, 0xFF2E6675, 0xFF5E8585, 0xFF7D785E, [
        ("glow", 0.24, 0.10, 0.86, 0xB8FFFFFF), ("glow", 0.74, 0.48, 0.82, 0x66B3F7FF), ("glow", 0.46, 0.88, 0.78, 0x5BFFF0BD),
        ("flow", 0x45FFFFFF, 0.18, 0.48, 0.42, -0.10), ("flow", 0x34B5F6FF, 0.44, 0.74, 0.34, 0.12), ("haze", 0x26FFFFFF, 0.52, 0.46, 1.10),
    ]),
    template("forest_mist", False, False, 0xFF08241F, 0xFF1D5448, 0xFF123930, [
        ("glow", 0.18, 0.14, 0.72, 0x5250A65E), ("glow", 0.78, 0.28, 0.72, 0x557DB852), ("glow", 0.46, 0.88, 0.76, 0x5057D9A7),
        ("flow", 0x385D9D55, 0.10, 0.32, 0.32, 0.14), ("flow", 0x497BD086, 0.50, 0.72, 0.38, -0.10), ("arc", 0.08, 0.24, 0.34, 0x4FA6D36A, 0.014, 0.018, 210, 82), ("arc", 0.96, 0.92, 0.42, 0x4A9BC871, 0.014, 0.018, 210, 88), ("haze", 0x18FFFFFF, 0.30, 0.46, 1.00),
    ]),
    template("daylight_minimal", True, True, 0xFF4A5C6C, 0xFF637482, 0xFF747E86, [
        ("glow", 0.14, 0.12, 0.82, 0xB0FFFFFF), ("glow", 0.82, 0.72, 0.74, 0x42C5D8E8), ("flow", 0x36FFFFFF, 0.28, 0.58, 0.34, 0.06), ("arc", 0.66, 1.12, 0.48, 0x30A9B9CA, 0.010, 0.014, 206, 104), ("haze", 0x22FFFFFF, 0.44, 0.46, 1.06),
    ]),
    template("deep_sea", False, False, 0xFF031927, 0xFF0D4C64, 0xFF092231, [
        ("stars", 50), ("glow", 0.80, 0.28, 0.70, 0x3848D6FF), ("glow", 0.18, 0.82, 0.76, 0x404DA3C8), ("current", 0x56A8D7E8, 0.54, -0.08), ("current", 0x3DE2F8FF, 0.64, 0.04), ("arc", 0.22, 0.94, 0.48, 0x5CBFEAFF, 0.010, 0.016, 210, 100), ("haze", 0x16FFFFFF, 0.42, 0.56, 1.02),
    ]),
    template("violet_smoke", False, False, 0xFF070515, 0xFF2C1355, 0xFF0B0722, [
        ("glow", 0.54, 0.46, 0.46, 0x9A9A38FF), ("glow", 0.62, 0.26, 0.54, 0x6A4422FF), ("glow", 0.44, 0.74, 0.48, 0x724DB0FF), ("flow", 0x50B065FF, 0.18, 0.48, 0.28, 0.12), ("flow", 0x3DDC72FF, 0.52, 0.82, 0.34, -0.08), ("haze", 0x20FFFFFF, 0.52, 0.46, 0.92),
    ]),
    template("rose_veil", True, True, 0xFF7E566D, 0xFF936A98, 0xFF5C7790, [
        ("glow", 0.84, 0.18, 0.78, 0x6CFFC3E6), ("glow", 0.44, 0.62, 0.72, 0x54E6B2FF), ("glow", 0.16, 0.86, 0.66, 0x42BFF7FF), ("flow", 0x48FFFFFF, 0.42, 0.70, 0.34, -0.04), ("arc", 0.38, 0.62, 0.36, 0x44FFA6E7, 0.010, 0.016, 322, 124), ("haze", 0x28FFFFFF, 0.42, 0.44, 1.10),
    ]),
    template("emerald_aurora", False, False, 0xFF021513, 0xFF0E4A44, 0xFF020B0B, [
        ("stars", 70), ("glow", 0.50, 0.46, 0.76, 0x6520D88B), ("glow", 0.80, 0.30, 0.66, 0x553BDCA4), ("current", 0x5A27F0A3, 0.34, -0.08), ("current", 0x4532C4FF, 0.54, 0.08), ("arc", 0.74, 0.92, 0.54, 0x5A47F5BD, 0.009, 0.018, 198, 100), ("haze", 0x16FFFFFF, 0.44, 0.46, 1.02),
    ]),
    template("blue_silk", True, False, 0xFF37677B, 0xFF4E8EA3, 0xFF4B7896, [
        ("glow", 0.20, 0.14, 0.84, 0x7FFFFFFF), ("glow", 0.84, 0.38, 0.72, 0x66BDEEFF), ("current", 0x62FFFFFF, 0.26, -0.08), ("current", 0x4FBDEBFF, 0.70, 0.12), ("arc", 0.80, 0.08, 0.42, 0x4EFFFFFF, 0.010, 0.018, 128, 108), ("haze", 0x22FFFFFF, 0.46, 0.44, 1.08),
    ]),
    template("peach_dawn", True, False, 0xFF885C54, 0xFFA2725D, 0xFF87658A, [
        ("glow", 0.18, 0.12, 0.86, 0x7EFFFFFF), ("glow", 0.78, 0.76, 0.78, 0x60F28BFF), ("glow", 0.30, 0.84, 0.76, 0x55FFD58A), ("flow", 0x42FFFFFF, 0.20, 0.52, 0.42, 0.08), ("flow", 0x38F2B4FF, 0.48, 0.80, 0.36, -0.10), ("haze", 0x24FFFFFF, 0.46, 0.42, 1.08),
    ]),
    template("graphite_smoke", False, False, 0xFF090B10, 0xFF262D36, 0xFF11151C, [
        ("glow", 0.20, 0.82, 0.70, 0x3A8CA5C8), ("glow", 0.72, 0.52, 0.62, 0x35586683), ("flow", 0x3A8998A8, 0.46, 0.66, 0.34, -0.08), ("flow", 0x30FFFFFF, 0.58, 0.78, 0.30, 0.10), ("current", 0x335D7184, 0.72, -0.04), ("haze", 0x14FFFFFF, 0.36, 0.52, 0.96),
    ]),
    template("pastel_prism", True, True, 0xFF63769C, 0xFF88699B, 0xFF5A8E96, [
        ("glow", 0.18, 0.20, 0.84, 0x64BDEBFF), ("glow", 0.78, 0.22, 0.78, 0x66FFB5D4), ("glow", 0.62, 0.84, 0.76, 0x58FFF0A8), ("flow", 0x45FFFFFF, 0.24, 0.56, 0.40, 0.08), ("arc", 0.82, 0.06, 0.50, 0x52FFB6F0, 0.010, 0.016, 112, 124), ("arc", 0.18, 1.02, 0.48, 0x48B5F2FF, 0.010, 0.016, 216, 100), ("haze", 0x24FFFFFF, 0.48, 0.46, 1.10),
    ]),
    template("midnight_moon", False, False, 0xFF030A23, 0xFF15165A, 0xFF050916, [
        ("stars", 58), ("glow", 0.82, 0.62, 0.54, 0x613C21B9), ("glow", 0.74, 0.84, 0.44, 0x6B622CFF), ("bubble", 0.78, 0.56, 0.23, 0x233D22C6, 0x40A78CFF), ("arc", 0.86, 0.88, 0.42, 0x6E9A65FF, 0.010, 0.018, 198, 104), ("haze", 0x14FFFFFF, 0.42, 0.48, 1.00),
    ]),
    template("cyan_crystal", True, False, 0xFF16849A, 0xFF238DAA, 0xFF4D68AA, [
        ("glow", 0.18, 0.14, 0.80, 0x84FFFFFF), ("glow", 0.82, 0.72, 0.84, 0x795C8DFF), ("flow", 0x55FFFFFF, 0.28, 0.58, 0.36, -0.10), ("current", 0x6B13B8FF, 0.62, 0.08), ("bubble", 0.96, 0.48, 0.22, 0x1FFFFFFF, 0x70FFFFFF), ("arc", 0.98, 0.48, 0.24, 0x72FFFFFF, 0.010, 0.014, 138, 210), ("haze", 0x24FFFFFF, 0.42, 0.44, 1.08),
    ]),
    template("lavender_crystal", True, True, 0xFF715CAC, 0xFF8974B5, 0xFF715B9B, [
        ("glow", 0.16, 0.18, 0.82, 0x66FFFFFF), ("glow", 0.84, 0.78, 0.78, 0x78C79BFF), ("glow", 0.18, 0.82, 0.60, 0x48FFB5E3), ("flow", 0x46FFFFFF, 0.36, 0.64, 0.38, -0.08), ("bubble", 1.02, 0.82, 0.24, 0x22B69CFF, 0x6FFFFFFF), ("arc", 0.90, 0.72, 0.30, 0x64FFFFFF, 0.010, 0.018, 132, 160), ("haze", 0x28FFFFFF, 0.48, 0.46, 1.10),
    ]),
]


def argb(value):
    return (value >> 16 & 255, value >> 8 & 255, value & 255, value >> 24 & 255)


def clamp(value, low, high):
    return max(low, min(high, value))


def color_variant(value, rng, amount=0.018):
    r, g, b, a = argb(value)
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    h = (h + rng.uniform(-amount, amount)) % 1.0
    s = clamp(s * rng.uniform(0.96, 1.04), 0, 1)
    v = clamp(v * rng.uniform(0.96, 1.04), 0, 1)
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    a = int(clamp(a * rng.uniform(0.90, 1.08), 0, 255))
    return int(r * 255), int(g * 255), int(b * 255), a


def scale_size(size, factor=4):
    return max(2, size[0] // factor), max(2, size[1] // factor)


def linear_gradient(size, colors, stops):
    small = scale_size(size)
    img = Image.new("RGBA", small)
    pix = img.load()
    colors = [argb(color) for color in colors]
    for y in range(small[1]):
        for x in range(small[0]):
            t = (x / max(1, small[0] - 1) + y / max(1, small[1] - 1)) * 0.5
            index = 0 if t <= stops[1] else 1
            low, high = stops[index], stops[index + 1]
            p = clamp((t - low) / max(0.001, high - low), 0, 1)
            c1, c2 = colors[index], colors[index + 1]
            pix[x, y] = tuple(int(c1[i] * (1 - p) + c2[i] * p) for i in range(4))
    return img.resize(size, Image.Resampling.BICUBIC)


def overlay_linear(img, top, bottom, vertical=True):
    size = img.size
    small = scale_size(size)
    layer = Image.new("RGBA", small)
    pix = layer.load()
    c1, c2 = argb(top), argb(bottom)
    for y in range(small[1]):
        for x in range(small[0]):
            t = y / max(1, small[1] - 1) if vertical else x / max(1, small[0] - 1)
            pix[x, y] = tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(4))
    return Image.alpha_composite(img, layer.resize(size, Image.Resampling.BICUBIC))


def radial_layer(size, cx, cy, radius, color, rng, stops=None, basis="width"):
    small = scale_size(size)
    sw, sh = small
    layer = Image.new("RGBA", small, (0, 0, 0, 0))
    pix = layer.load()
    x0 = cx * sw + rng.uniform(-0.018, 0.018) * sw
    y0 = cy * sh + rng.uniform(-0.018, 0.018) * sh
    base = max(sw, sh) if basis == "max" else sw
    r = max(1, radius * base * rng.uniform(0.94, 1.06))
    base = color_variant(color, rng, 0.010)
    for y in range(sh):
        for x in range(sw):
            d = math.hypot(x - x0, y - y0) / r
            if d >= 1:
                continue
            if stops:
                if d < stops[0][0]:
                    a = stops[0][1]
                elif d < stops[1][0]:
                    a = int(stops[0][1] * (1 - (d - stops[0][0]) / (stops[1][0] - stops[0][0])) + stops[1][1] * ((d - stops[0][0]) / (stops[1][0] - stops[0][0])))
                else:
                    a = int(stops[1][1] * (1 - (d - stops[1][0]) / (1 - stops[1][0])))
            else:
                a = int(base[3] * (1 - d) ** 1.4)
            pix[x, y] = (base[0], base[1], base[2], clamp(a, 0, 255))
    return layer.resize(size, Image.Resampling.BICUBIC)


def cubic(p0, p1, p2, p3, t):
    mt = 1 - t
    return (
        mt ** 3 * p0[0] + 3 * mt ** 2 * t * p1[0] + 3 * mt * t ** 2 * p2[0] + t ** 3 * p3[0],
        mt ** 3 * p0[1] + 3 * mt ** 2 * t * p1[1] + 3 * mt * t ** 2 * p2[1] + t ** 3 * p3[1],
    )


def curve_points(*points, steps=48):
    return [cubic(*points, i / steps) for i in range(steps + 1)]


def draw_shape(img, points, color, blur):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).polygon(points, fill=color)
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    return Image.alpha_composite(img, layer)


def draw_flow(img, color, start, end, thickness, phase, rng):
    width, height = img.size
    start += rng.uniform(-0.025, 0.025)
    end += rng.uniform(-0.025, 0.025)
    phase += rng.uniform(-0.025, 0.025)
    p = height * phase
    top = curve_points((-width * 0.18, height * start + p), (width * 0.22, height * (start - 0.18) - p), (width * 0.54, height * (end - 0.28)), (width * 1.18, height * (end - 0.12) + p))
    bottom = curve_points((width * 1.18, height * (end + thickness * 0.36) + p), (width * 0.58, height * (end + thickness * 0.12)), (width * 0.28, height * (start + thickness * 0.58) + p), (-width * 0.18, height * (start + thickness * 0.42) - p))
    return draw_shape(img, top + bottom, color_variant(color, rng, 0.010), max(18, width * 0.030))


def draw_current(img, color, y, phase, rng, beam=False):
    width, height = img.size
    y += rng.uniform(-0.020, 0.020)
    phase += rng.uniform(-0.020, 0.020)
    p = height * phase
    if beam:
        top = curve_points((-width * 0.16, height * (y + 0.08) + p), (width * 0.22, height * (y - 0.05) + p), (width * 0.52, height * (y + 0.06) - p), (width * 1.14, height * (y - 0.22) + p))
        bottom = curve_points((width * 1.14, height * (y - 0.08) + p), (width * 0.60, height * (y + 0.20) - p), (width * 0.28, height * (y + 0.08) + p), (-width * 0.16, height * (y + 0.22) - p))
        return draw_shape(img, top + bottom, color_variant(color, rng, 0.010), max(10, width * 0.022))
    top = curve_points((-width * 0.12, height * y + p), (width * 0.16, height * (y - 0.16)), (width * 0.38, height * (y + 0.20)), (width * 0.64, height * (y + 0.02)), steps=28)
    top += curve_points((width * 0.64, height * (y + 0.02)), (width * 0.86, height * (y - 0.12)), (width * 1.04, height * (y + 0.02)), (width * 1.14, height * (y - 0.04)), steps=28)
    bottom = curve_points((width * 1.14, height * (y + 0.16)), (width * 0.78, height * (y + 0.30)), (width * 0.44, height * (y + 0.04)), (-width * 0.12, height * (y + 0.22)), steps=40)
    return draw_shape(img, top + bottom, color_variant(color, rng, 0.010), max(16, width * 0.026))


def draw_arc(img, cx, cy, radius, color, stroke, blur, start, sweep, rng):
    width, height = img.size
    cx += rng.uniform(-0.018, 0.018)
    cy += rng.uniform(-0.018, 0.018)
    radius *= rng.uniform(0.94, 1.06)
    r = width * radius
    points = []
    for index in range(80):
        angle = math.radians(start + sweep * index / 79)
        points.append((width * cx + math.cos(angle) * r, height * cy + math.sin(angle) * r))
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    draw.line(points, fill=color_variant(color, rng, 0.010), width=max(2, int(width * stroke)), joint="curve")
    layer = layer.filter(ImageFilter.GaussianBlur(max(6, width * blur)))
    return Image.alpha_composite(img, layer)


def draw_bubble(img, cx, cy, radius, fill, rim, rng):
    width, height = img.size
    cx += rng.uniform(-0.018, 0.018)
    cy += rng.uniform(-0.018, 0.018)
    radius *= rng.uniform(0.94, 1.06)
    x, y, r = width * cx, height * cy, width * radius
    img = Image.alpha_composite(img, radial_layer(img.size, cx - radius * 0.28, cy - radius * 0.16, radius * 1.35, fill, rng, stops=((0, 130), (0.44, argb(fill)[3]))))
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    draw.ellipse((x - r, y - r, x + r, y + r), outline=color_variant(rim, rng, 0.010), width=max(2, int(width * 0.004)))
    layer = layer.filter(ImageFilter.GaussianBlur(max(3, width * 0.006)))
    return Image.alpha_composite(img, layer)


def draw_stars(img, count, rng):
    width, height = img.size
    draw = ImageDraw.Draw(img)
    for _ in range(count):
        x, y = rng.random() * width, rng.random() * height
        alpha = rng.randint(18, 62)
        draw.point((x, y), fill=(210, 240, 255, alpha))


def add_grain(img, rng):
    width, height = img.size
    draw = ImageDraw.Draw(img)
    count = max(180, min(620, width * height // 4200))
    for _ in range(count):
        x, y = rng.random() * width, rng.random() * height
        alpha = rng.randint(5, 14)
        draw.point((x, y), fill=(255, 255, 255, alpha))


def add_readability(img, light, high):
    width, height = img.size
    if light:
        img = Image.alpha_composite(img, radial_layer(img.size, 0.50, 0.42, 0.86, 0x22000000, random.Random(1), basis="max"))
        img = overlay_linear(img, 0x00FFFFFF, 0x18000000)
        top, center, bottom = (0x3A000000, 0x46000000, 0x52000000) if high else (0x28000000, 0x34000000, 0x40000000)
        return Image.alpha_composite(img, linear_gradient(img.size, [top, center, bottom], [0, 0.54, 1]))
    img = Image.alpha_composite(img, radial_layer(img.size, 0.50, 0.50, 0.78, 0x66000000, random.Random(2), basis="max"))
    return overlay_linear(img, 0x14000000, 0x52000000)


def render(template_data, size, rng):
    bg = linear_gradient(size, [template_data["top"], template_data["center"], template_data["bottom"]], [0, 0.50 if template_data["light"] else 0.48, 1])
    bg = overlay_linear(bg, 0x16000000 if template_data["light"] else 0x44000000, 0x00000000, vertical=False)
    for op in template_data["ops"]:
        kind = op[0]
        if kind == "glow":
            bg = Image.alpha_composite(bg, radial_layer(size, op[1], op[2], op[3], op[4], rng))
        elif kind == "flow":
            bg = draw_flow(bg, op[1], op[2], op[3], op[4], op[5], rng)
        elif kind == "current":
            bg = draw_current(bg, op[1], op[2], op[3], rng)
        elif kind == "beam":
            bg = draw_current(bg, op[1], op[2], op[3], rng, beam=True)
        elif kind == "haze":
            bg = Image.alpha_composite(bg, radial_layer(size, op[2], op[3], op[4], op[1], rng, basis="max"))
        elif kind == "arc":
            bg = draw_arc(bg, op[1], op[2], op[3], op[4], op[5], op[6], op[7], op[8], rng)
        elif kind == "bubble":
            bg = draw_bubble(bg, op[1], op[2], op[3], op[4], op[5], rng)
        elif kind == "stars":
            draw_stars(bg, op[1], rng)
    bg = add_readability(bg, template_data["light"], template_data["high"])
    add_grain(bg, rng)
    return bg.convert("RGB")


def save_webp(img, path, quality):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "WEBP", quality=quality, method=6)


def render_job(job):
    index, item_seed, template_index, target_name, out_dir, quality, prefix = job
    template_data = TEMPLATES[template_index]
    rng = random.Random(item_seed)
    image = render(template_data, SIZES[target_name], rng)
    file = out_dir / target_name / f"{prefix}_{index:02d}_{template_data['name']}.webp"
    save_webp(image, file, clamp(quality, 1, 100))
    return file


def get_worker_count(workers, job_count):
    if workers is not None:
        return max(1, min(workers, job_count))
    return max(1, min(os.cpu_count() or 1, job_count))


def main(count, out_dir, seed, quality, target, prefix, workers):
    seed = seed if seed is not None else time.time_ns()
    master = random.Random(seed)
    targets = list(SIZES.keys()) if target == "both" else [target]
    jobs = []
    for index in range(1, count + 1):
        item_seed = master.randrange(1 << 63)
        template_index = master.randrange(len(TEMPLATES))
        for target_name in targets:
            jobs.append((index, item_seed, template_index, target_name, out_dir, quality, prefix))
    worker_count = get_worker_count(workers, len(jobs))
    print(f"seed={seed}")
    print(f"out={out_dir}")
    print(f"workers={worker_count}")
    if worker_count == 1:
        for job in jobs:
            file = render_job(job)
            print(file)
        return
    with ProcessPoolExecutor(max_workers=worker_count) as executor:
        for file in executor.map(render_job, jobs):
            print(file)


if __name__ == "__main__":
    COUNT = 8
    OUT_DIR = Path.cwd() / "tmp"
    SEED = None
    QUALITY = 72
    TARGET = "both"
    PREFIX = "wallpaper_random"
    WORKERS = None

    main(COUNT, OUT_DIR, SEED, QUALITY, TARGET, PREFIX, WORKERS)
