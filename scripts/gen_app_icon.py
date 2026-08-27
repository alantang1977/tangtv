#!/usr/bin/env python3
"""生成 MO TV 应用图标全套资源（位图 + VectorDrawable）。

设计目标：在一屏同质化的浅色立方体图标里能远距离认出来。
  - 紫罗兰 #8B5CF6 -> 粉红 #F472B6 对角渐变实心底。
  - 纯白立体几何 T / J 字标，用挤压法模拟 3D 效果，赋予现代感。
  - 字标收在自适应图标 66dp 安全区内，被系统裁成圆形也不缺字。

覆盖范围：启动器图标、TV 横幅、Play 商店图、应用内标题栏 logo、通知栏小图标、
网页管理端 favicon。这些是全部承载 App 形象的位置，改图标时必须整套重跑。

用法:
  py scripts/gen_app_icon.py --preview   只输出预览图到 build/icon-preview/
  py scripts/gen_app_icon.py             写入 app/src/**/res/
"""

import argparse
import os
import time

from PIL import Image, ImageDraw

# 紫罗兰 → 粉红渐变
GRAD_A = (0x8B, 0x5C, 0xF6)   # 左上 紫罗兰
GRAD_B = (0xF4, 0x72, 0xB6)   # 右下 粉红
GRAD_A_HEX = "#8B5CF6"
GRAD_B_HEX = "#F472B6"
WHITE = (255, 255, 255, 255)
HOLE = (0, 0, 0, 0)
LIGHT_GRAY = (208, 208, 208, 255)   # 左面
DARK_GRAY  = (160, 160, 160, 255)   # 右面

SS = 4          # 超采样倍率，先大图绘制再降采样得到干净边缘
VIEWPORT = 512  # VectorDrawable 视口边长

# 渐变内缩比例（沿用原逻辑）
GRAD_INSET_ADAPTIVE = 1.0 / 6.0
GRAD_INSET_CIRCLE = 0.1464
GRAD_INSET_ROUNDED = 0.0644
GRAD_INSET_SQUARE = 0.0

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# --- 字标几何参数（T 和 J，全部以 M 的 cap height h 为单位）---
R_STEM = 0.26          # 笔画宽度 / h
R_T_W = 1.0            # T 横条宽度 / h
R_J_W = 0.5            # J 竖干宽度 / h（不含钩子伸出）
R_GAP = 0.1            # T 与 J 的间距 / h
R_TOTAL = R_T_W + R_GAP + R_J_W

# 字标占画面宽度的比例（沿用原值）
FILL_SAFE = 0.52
FILL_LEGACY = 0.70
FILL_CIRCLE = 0.64
FILL_NOTIFY = 0.88

# 小尺寸降级阈值
WORDMARK_MIN_PX = 32
FILL_BADGE = 0.68

# 3D 挤压参数
THICK_RATIO = 0.18      # 厚度 / h
OFFSET_RATIO = 0.30     # 水平偏移 / 厚度（等距透视）


def layout(size, fill):
    """按画面尺寸和填充比算出字标的基准几何。

    返回 (x0, y0, h)：x0 是字标左边界，h 是 cap height，
    y0 是字标顶边。
    """
    total_w = size * fill
    h = total_w / R_TOTAL
    x0 = (size - total_w) / 2
    y0 = (size - h) / 2
    return x0, y0, h


def draw_wordmark(size, fill):
    """绘制 3D 立体 T 和 J 字标（RGBA 图层）。

    若 fill == FILL_NOTIFY，则绘制扁平白色轮廓（用于通知）。
    """
    layer = Image.new("RGBA", (size, size), HOLE)
    d = ImageDraw.Draw(layer)
    x0, y0, h = layout(size, fill)
    s = h * R_STEM
    thick = h * THICK_RATIO
    off = thick * OFFSET_RATIO

    # ----- T 的几何 -----
    t_bar_w = h * R_T_W          # 横条宽度
    t_bar_h = s                  # 横条高度
    t_stem_x = x0 + (t_bar_w - s) / 2
    t_stem_y = y0 + s
    t_stem_h = h - s

    # ----- J 的几何（钩子向左）-----
    j_x = x0 + h * (R_T_W + R_GAP)
    j_stem_w = s
    j_stem_h = h
    # 钩子（小矩形）向左延伸
    hook_w = s * 0.8          # 钩子长度
    hook_h = s * 0.5          # 钩子高度
    hook_x = j_x - hook_w     # 从竖干左边界向左伸出
    hook_y = y0 + h - hook_h  # 底部对齐

    if fill == FILL_NOTIFY:
        # 扁平白色轮廓（仅顶面）
        # T 横条
        d.rectangle([x0, y0, x0 + t_bar_w, y0 + t_bar_h], fill=WHITE)
        # T 竖干
        d.rectangle([t_stem_x, t_stem_y, t_stem_x + s, t_stem_y + t_stem_h], fill=WHITE)
        # J 竖干
        d.rectangle([j_x, y0, j_x + j_stem_w, y0 + j_stem_h], fill=WHITE)
        # J 钩子（向左）
        d.rectangle([hook_x, hook_y, hook_x + hook_w, hook_y + hook_h], fill=WHITE)
        return layer

    # 3D 立体绘制：先绘制所有块的右面、左面、顶面（按顺序）
    # 定义各个块的矩形参数： (x, y, w, h)
    blocks = [
        # T 横条
        (x0, y0, t_bar_w, t_bar_h),
        # T 竖干
        (t_stem_x, t_stem_y, s, t_stem_h),
        # J 竖干
        (j_x, y0, j_stem_w, j_stem_h),
        # J 钩子（向左）
        (hook_x, hook_y, hook_w, hook_h),
    ]

    # 先画所有右面，再左面，最后顶面（顶面覆盖侧面）
    for x, y, w, bh in blocks:
        # 右面（深灰）
        d.polygon([(x + w, y), (x + w + off, y + thick),
                   (x + w + off, y + bh + thick), (x + w, y + bh)],
                  fill=DARK_GRAY)
    for x, y, w, bh in blocks:
        # 左面（浅灰）
        d.polygon([(x, y), (x, y + bh),
                   (x + off, y + bh + thick), (x + off, y + thick)],
                  fill=LIGHT_GRAY)
    for x, y, w, bh in blocks:
        # 顶面（白色）
        d.polygon([(x, y), (x + w, y), (x + w, y + bh), (x, y + bh)],
                  fill=WHITE)

    return layer


def draw_badge(size):
    """只画 J 的扁平轮廓（用于小尺寸降级），钩子向左。"""
    layer = Image.new("RGBA", (size, size), HOLE)
    d = ImageDraw.Draw(layer)
    s = size * 0.2
    h = size * 0.8
    x = (size - s) / 2
    y = (size - h) / 2
    # 竖干
    d.rectangle([x, y, x + s, y + h], fill=WHITE)
    # 钩子（向左）
    hook_w = s * 0.8
    hook_h = s * 0.5
    hook_x = x - hook_w          # 从竖干左边界向左伸出
    hook_y = y + h - hook_h
    d.rectangle([hook_x, hook_y, hook_x + hook_w, hook_y + hook_h], fill=WHITE)
    return layer


# --- 位图绘制（渐变、遮罩等沿用原逻辑）---
def make_gradient(size, inset=0.0):
    """左上 -> 右下对角线性渐变。"""
    grad = Image.new("RGB", (size, size))
    px = grad.load()
    denom = 2.0 * (size - 1)
    span = max(1e-6, 1.0 - 2.0 * inset)
    for y in range(size):
        for x in range(size):
            t = (x + y) / denom
            t = min(1.0, max(0.0, (t - inset) / span))
            px[x, y] = (
                round(GRAD_A[0] + (GRAD_B[0] - GRAD_A[0]) * t),
                round(GRAD_A[1] + (GRAD_B[1] - GRAD_A[1]) * t),
                round(GRAD_A[2] + (GRAD_B[2] - GRAD_A[2]) * t),
            )
    return grad.convert("RGBA")


def _mask(size, shape, radius_ratio=0.0):
    m = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(m)
    if shape == "circle":
        md.ellipse([0, 0, size - 1, size - 1], fill=255)
    elif shape == "rounded":
        md.rounded_rectangle([0, 0, size - 1, size - 1],
                             radius=size * radius_ratio, fill=255)
    else:
        md.rectangle([0, 0, size - 1, size - 1], fill=255)
    return m


def render(size, shape="rounded", fill=FILL_LEGACY, radius_ratio=0.22,
           inset=None, badge=None):
    """渲染完整图标（渐变底 + 字标 + 外形裁切）。"""
    if inset is None:
        inset = {"circle": GRAD_INSET_CIRCLE,
                 "rounded": GRAD_INSET_ROUNDED}.get(shape, GRAD_INSET_SQUARE)
    if badge is None:
        badge = size < WORDMARK_MIN_PX
    big = size * SS
    img = make_gradient(big, inset)
    img.alpha_composite(draw_badge(big) if badge else draw_wordmark(big, fill))
    if shape != "square":
        img.putalpha(_mask(big, shape, radius_ratio))
    return img.resize((size, size), Image.LANCZOS)


def render_banner(w, h):
    """Android TV banner 320x180。"""
    bw, bh = w * SS, h * SS
    img = make_gradient(max(bw, bh)).resize((bw, bh), Image.LANCZOS)
    mark_box = int(bh * 0.62)
    mark = draw_wordmark(mark_box, 0.92)
    img.alpha_composite(mark, (int(bw * 0.075), int((bh - mark_box) / 2)))
    return img.resize((w, h), Image.LANCZOS)


def render_notification(size):
    """通知栏小图标：纯白扁平轮廓。"""
    return draw_wordmark(size * SS, FILL_NOTIFY).resize((size, size),
                                                        Image.LANCZOS)


# --- VectorDrawable 生成（支持 3D 多色）---
def _p(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def wordmark_paths(fill):
    """返回 (顶面路径, 左面路径, 右面路径)，坐标基于 512 视口。

    若 fill == FILL_NOTIFY，则只返回扁平轮廓（三个面均为同一轮廓）。
    """
    x0, y0, h = layout(VIEWPORT, fill)
    s = h * R_STEM
    thick = h * THICK_RATIO
    off = thick * OFFSET_RATIO

    t_bar_w = h * R_T_W
    t_bar_h = s
    t_stem_x = x0 + (t_bar_w - s) / 2
    t_stem_y = y0 + s
    t_stem_h = h - s

    j_x = x0 + h * (R_T_W + R_GAP)
    j_stem_w = s
    j_stem_h = h
    # 钩子向左
    hook_w = s * 0.8
    hook_h = s * 0.5
    hook_x = j_x - hook_w
    hook_y = y0 + h - hook_h

    # 所有块的矩形 (x, y, w, bh)
    blocks = [
        (x0, y0, t_bar_w, t_bar_h),
        (t_stem_x, t_stem_y, s, t_stem_h),
        (j_x, y0, j_stem_w, j_stem_h),
        (hook_x, hook_y, hook_w, hook_h),
    ]

    def rect_to_path(x, y, w, bh):
        return f"M{_p(x)},{_p(y)}L{_p(x+w)},{_p(y)}L{_p(x+w)},{_p(y+bh)}L{_p(x)},{_p(y+bh)}z"

    if fill == FILL_NOTIFY:
        # 扁平轮廓：所有矩形合并为一个路径
        paths = [rect_to_path(*b) for b in blocks]
        return "".join(paths), "", ""

    # 3D 多面：分别收集顶面、左面、右面
    top_paths = []
    left_paths = []
    right_paths = []
    for x, y, w, bh in blocks:
        top_paths.append(rect_to_path(x, y, w, bh))
        # 左面
        left_paths.append(
            f"M{_p(x)},{_p(y)}L{_p(x)},{_p(y+bh)}"
            f"L{_p(x+off)},{_p(y+bh+thick)}L{_p(x+off)},{_p(y+thick)}z"
        )
        # 右面
        right_paths.append(
            f"M{_p(x+w)},{_p(y)}L{_p(x+w+off)},{_p(y+thick)}"
            f"L{_p(x+w+off)},{_p(y+bh+thick)}L{_p(x+w)},{_p(y+bh)}z"
        )
    return "".join(top_paths), "".join(left_paths), "".join(right_paths)


def vector_wordmark(fill, color="#FFFFFF", size_dp=108):
    """生成字标的 VectorDrawable。

    若 fill == FILL_NOTIFY，生成扁平白色轮廓（单路径）。
    否则生成 3D 三色路径（顶面白色，左面浅灰，右面深灰）。
    """
    top, left, right = wordmark_paths(fill)
    if fill == FILL_NOTIFY:
        return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/gen_app_icon.py 生成，请勿手工编辑 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp"
    android:height="{size_dp}dp"
    android:viewportWidth="{VIEWPORT}"
    android:viewportHeight="{VIEWPORT}">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="{top}" />
</vector>
"""
    # 3D 多色：顶面白色，左面浅灰，右面深灰
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/gen_app_icon.py 生成，请勿手工编辑 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp"
    android:height="{size_dp}dp"
    android:viewportWidth="{VIEWPORT}"
    android:viewportHeight="{VIEWPORT}">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="{top}" />
    <path
        android:fillColor="#D0D0D0"
        android:pathData="{left}" />
    <path
        android:fillColor="#A0A0A0"
        android:pathData="{right}" />
</vector>
"""


def vector_background():
    """自适应图标背景：对角线性渐变（紫罗兰→粉红）。"""
    lo = VIEWPORT * GRAD_INSET_ADAPTIVE
    hi = VIEWPORT - lo
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/gen_app_icon.py 生成，请勿手工编辑 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="{VIEWPORT}"
    android:viewportHeight="{VIEWPORT}">
    <path android:pathData="M0,0h{VIEWPORT}v{VIEWPORT}h-{VIEWPORT}z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt"
            name="android:fillColor">
            <gradient
                android:startX="{_p(lo)}"
                android:startY="{_p(lo)}"
                android:endX="{_p(hi)}"
                android:endY="{_p(hi)}"
                android:type="linear"
                android:tileMode="clamp">
                <item android:offset="0" android:color="{GRAD_A_HEX}" />
                <item android:offset="1" android:color="{GRAD_B_HEX}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

BANNER_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_banner_foreground" />
</adaptive-icon>
"""


# --- 输出清单（完全沿用原结构）---
DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
             ("xxhdpi", 144), ("xxxhdpi", 192)]
NOTIFY_DENSITIES = [("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72)]
LOGO_PX = 600
FAVICON_SIZES = [16, 32, 48]
MAIN_RES = "app/src/main/res"


def save_img(img, rel, **kw):
    path = os.path.join(REPO, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for attempt in range(8):
        try:
            img.save(path, **kw)
            break
        except OSError:
            if attempt == 7:
                raise
            time.sleep(0.25)
    print(f"  {rel:62s} {img.size[0]}x{img.size[1]}")


def save_text(text, rel):
    path = os.path.join(REPO, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"  {rel}")


def do_preview():
    out = "build/icon-preview"
    print(f"[preview] -> {out}/")
    save_img(render(512, "rounded"), f"{out}/rounded_512.png")
    save_img(render(512, "circle", fill=FILL_CIRCLE), f"{out}/circle_512.png")
    save_img(render(512, "square"), f"{out}/square_512.png")
    save_img(render(48, "rounded"), f"{out}/rounded_48.png")
    save_img(render(72, "rounded"), f"{out}/rounded_72.png")
    save_img(render(96, "rounded"), f"{out}/rounded_96.png")
    save_img(render_banner(320, 180), f"{out}/banner.png")
    save_img(render(432, "circle", fill=FILL_SAFE), f"{out}/adaptive_circle.png")
    save_img(render(432, "rounded", fill=FILL_SAFE, radius_ratio=0.30),
             f"{out}/adaptive_squircle.png")
    mono = Image.new("RGBA", (432, 432), (0x1F, 0x1F, 0x1F, 255))
    mono.alpha_composite(draw_wordmark(432, FILL_SAFE))
    save_img(mono, f"{out}/monochrome.png")
    save_img(render(LOGO_PX, "circle", fill=FILL_CIRCLE), f"{out}/logo.png")
    for px in FAVICON_SIZES:
        save_img(render(px, "circle", fill=FILL_CIRCLE).resize(
            (px * 8, px * 8), Image.NEAREST), f"{out}/favicon_{px}.png")
    for px in (24, 36, 48, 72):
        bar = Image.new("RGBA", (px, px), (0x20, 0x21, 0x24, 255))
        bar.alpha_composite(render_notification(px))
        save_img(bar.resize((px * 8, px * 8), Image.NEAREST),
                 f"{out}/notification_{px}.png")


def do_write():
    print("[vector drawables]")
    save_text(vector_background(), f"{MAIN_RES}/drawable/ic_launcher_background.xml")
    save_text(vector_wordmark(FILL_SAFE), f"{MAIN_RES}/drawable/ic_launcher_foreground.xml")
    save_text(vector_wordmark(FILL_SAFE),
              f"{MAIN_RES}/drawable/ic_launcher_monochrome.xml")
    save_text(ADAPTIVE_XML, f"{MAIN_RES}/mipmap-anydpi-v26/ic_launcher.xml")
    save_text(ADAPTIVE_XML, f"{MAIN_RES}/mipmap-anydpi-v26/ic_launcher_round.xml")

    print("[legacy launcher bitmaps]")
    for name, base in DENSITIES:
        px = {"mdpi": 128, "hdpi": 192, "xhdpi": 256,
              "xxhdpi": 384, "xxxhdpi": 512}[name]
        save_img(render(px, "rounded"), f"{MAIN_RES}/mipmap-{name}/ic_launcher.png",
                 format="PNG")
        save_img(render(base, "circle", fill=FILL_CIRCLE),
                 f"{MAIN_RES}/mipmap-{name}/ic_launcher_round.webp",
                 format="WEBP", lossless=True, quality=100)

    print("[play store]")
    save_img(render(512, "square"), "app/src/main/ic_launcher-playstore.png",
             format="PNG")

    print("[tv banner]")
    save_text(vector_wordmark(FILL_SAFE),
              "app/src/leanback/res/drawable/ic_banner_foreground.xml")
    save_text(BANNER_XML, "app/src/leanback/res/mipmap-anydpi-v26/ic_banner.xml")
    save_img(render_banner(320, 180), "app/src/leanback/res/drawable/ic_banner.png",
             format="PNG")

    print("[in-app logo]")
    save_img(render(LOGO_PX, "circle", fill=FILL_CIRCLE),
             f"{MAIN_RES}/drawable-nodpi/ic_logo.png", format="PNG")

    print("[notification]")
    save_text(vector_wordmark(FILL_NOTIFY, size_dp=24),
              f"{MAIN_RES}/drawable-anydpi/ic_notification.xml")
    for name, px in NOTIFY_DENSITIES:
        save_img(render_notification(px),
                 f"{MAIN_RES}/drawable-{name}/ic_notification.png", format="PNG")

    print("[web favicon]")
    sizes = sorted(FAVICON_SIZES)
    frames = [render(px, "circle", fill=FILL_CIRCLE) for px in sizes]
    save_img(frames[-1], "app/src/main/assets/favicon.ico", format="ICO",
             sizes=[(px, px) for px in sizes],
             append_images=frames[:-1])
    print("\nDone.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true",
                    help="只输出预览图到 build/icon-preview/，不改 res/")
    args = ap.parse_args()
    do_preview() if args.preview else do_write()


if __name__ == "__main__":
    main()