#!/usr/bin/env python3
"""生成 TJ TV 应用图标全套资源（位图 + VectorDrawable）。

设计目标：融合 iPhone 17 主题（液态玻璃高光）与高级立体感（浮雕 3D）。
  - 流光背景：紫罗兰 -> 亮青 -> 洋红 -> 粉红平滑渐变。
  - 高级立体字标：T、J以及播放键全部包含环境阴影、微厚度挤出、内侧高光、微渐变。
  - 已彻底移除纯扁平风格选项。
  - “j”顶部圆圈内的紫色三角形已整合高级液态玻璃美化，且质心完美居中。
  - 核心优化：T、J及整体元素已按物理包围盒计算，完美居中于画布。

用法:
  py scripts/gen_app_icon.py --preview      只输出预览图到 build/icon-preview/
  py scripts/gen_app_icon.py --style 3d     以高级立体浮雕风格生成（默认开启）
  py scripts/gen_app_icon.py --style iphone17 以带光影的iPhone17风格生成
  py scripts/gen_app_icon.py                写入 app/src/**/res/
"""

import argparse
import os
import time

from PIL import Image, ImageDraw, ImageFilter

# 流光溢彩的多重渐变颜色（引入了亮青色作为视觉焦点，更现代）
GRAD_A = (0x8B, 0x5C, 0xF6)      # 左上 紫罗兰
GRAD_C = (0x5C, 0xBF, 0xF6)      # 新增：亮青 (流光过渡)
GRAD_MID = (0xF0, 0x9E, 0xE0)    # 中间 亮洋红
GRAD_B = (0xF4, 0x72, 0xB6)      # 右下 粉红
GRAD_A_HEX = "#8B5CF6"
GRAD_B_HEX = "#F472B6"
WHITE = (255, 255, 255, 255)
HOLE = (0, 0, 0, 0)

SS = 4          # 超采样倍率，先大图绘制再降采样得到干净边缘
VIEWPORT = 512  # VectorDrawable 视口边长

# 渐变内缩比例（沿用原逻辑）
GRAD_INSET_ADAPTIVE = 1.0 / 6.0
GRAD_INSET_CIRCLE = 0.1464
GRAD_INSET_ROUNDED = 0.0644
GRAD_INSET_SQUARE = 0.0

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# --- 字标几何参数（重新调整以平衡视觉大小）---
R_STEM = 0.26          # 笔画宽度 / h
R_T_W = 0.85           # T 横条宽度 / h (从 1.0 缩小至 0.85，不再突出占位)
R_J_W = 0.6            # J 占位宽度 / h (微调了 J 的定位间距)
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

# 3D 立体参数（已提高厚度强化立体感）
THICK_RATIO = 0.18      # 微小厚度 / h
OFFSET_RATIO = 0.35     # 偏移比例


def layout(size, fill):
    """按画面尺寸和填充比算出字标的基准几何。"""
    total_w = size * fill
    h = total_w / R_TOTAL
    x0 = (size - total_w) / 2
    y0 = (size - h) / 2
    return x0, y0, h


def draw_wordmark(size, fill, style="3d"):
    """绘制高级立体浮雕 T 和 J 字标（RGBA 图层）。
    
    支持风格：
    - 3d: 高级立体浮雕（含阴影、厚度、内侧高光、微渐变）
    - iphone17: 带OLED发光（兼具立体感）
    """
    layer = Image.new("RGBA", (size, size), HOLE)
    d = ImageDraw.Draw(layer)
    x0, y0, h = layout(size, fill)
    s = h * R_STEM
    thick = h * THICK_RATIO
    off = thick * OFFSET_RATIO

    # ----- T 的几何 -----
    t_bar_w = h * R_T_W          
    t_bar_h = s                  
    t_stem_x = x0 + (t_bar_w - s) / 2
    t_stem_y = y0 + s
    t_stem_h = h - s

    # ----- J 的几何（缩短主体，顶部圆点与T平齐，底部严格对齐）-----
    j_x = x0 + h * (R_T_W + R_GAP)
    j_stem_w = h * 0.32          # 加宽 J 的竖干，使其与 T 的粗细匹配
    
    # 顶部圆点不能超过 T 顶边，圆心与 T 顶边平齐
    j_dot_r = j_stem_w * 0.57
    j_dot_cx = j_x + j_stem_w / 2
    j_dot_cy = y0 + j_dot_r      # 圆心位于 y0 线上，圆点顶边刚好平齐 T 顶边
    
    # J竖干缩短，底部对齐 y0+h，顶部让出圆点和间隙
    gap = s * 0.15
    j_stem_y = j_dot_cy + j_dot_r + gap  # 竖干顶部位置
    j_stem_h = (y0 + h) - j_stem_y       # 缩短后的竖干高度
    
    # 钩子（向左延伸，底部对齐）
    hook_w = j_stem_w * 0.9     
    hook_h = j_stem_w * 0.55    
    hook_x = j_x - hook_w       
    hook_y = y0 + h - hook_h    

    # === 精确物理包围盒居中修正 ===
    real_left = min(x0, hook_x)
    real_right = max(x0 + t_bar_w, j_x + j_stem_w)
    real_w = real_right - real_left
    shift_x = (size - real_w) / 2 - real_left

    x0 += shift_x
    t_stem_x += shift_x
    j_x += shift_x
    hook_x += shift_x
    j_dot_cx += shift_x

    # 定义所有区块（修改 J 竖干的区块坐标）
    blocks = [
        (x0, y0, t_bar_w, t_bar_h),
        (t_stem_x, t_stem_y, s, t_stem_h),
        (j_x, j_stem_y, j_stem_w, j_stem_h),
        (hook_x, hook_y, hook_w, hook_h),
    ]

    if fill == FILL_NOTIFY:
        # 系统强制要求：通知栏小图标必须为纯白透明，无任何阴影和渐变
        for x, y, w, bh in blocks:
            d.rectangle([x, y, x + w, y + bh], fill=WHITE)
        d.ellipse([j_dot_cx - j_dot_r, j_dot_cy - j_dot_r,
                   j_dot_cx + j_dot_r, j_dot_cy + j_dot_r], fill=WHITE)
        return layer

    # === 绘制高级立体浮雕挤出层（带环境阴影） ===
    # 1. 先在底层绘制极深的柔和阴影（模拟悬浮）
    shadow_layer = Image.new("RGBA", (size, size), HOLE)
    sd = ImageDraw.Draw(shadow_layer)
    for x, y, w, bh in blocks:
        sd.rectangle([x + off, y + thick, x + w + off, y + bh + thick], fill=(60, 20, 80, 150))
    # 圆圈的深阴影
    sd.ellipse([j_dot_cx - j_dot_r + off, j_dot_cy - j_dot_r + thick,
                j_dot_cx + j_dot_r + off, j_dot_cy + j_dot_r + thick], fill=(60, 20, 80, 150))
    
    shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(radius=thick * 0.8))
    layer.alpha_composite(shadow_layer)

    # 2. 绘制主体的颜色挤出层（右面、底面偏暗色）
    for x, y, w, bh in blocks:
        d.rectangle([x + off, y + thick, x + w + off, y + bh + thick], fill=(120, 50, 140, 255)) # 深紫
    # 圆圈挤出层
    d.ellipse([j_dot_cx - j_dot_r + off, j_dot_cy - j_dot_r + thick,
               j_dot_cx + j_dot_r + off, j_dot_cy + j_dot_r + thick], fill=(120, 50, 140, 255))

    # === 绘制核心主体（含微渐变，模拟抛光表面）===
    # 绘制 T 和 J 表面（纯白渐变色，边缘带微蓝灰）
    for x, y, w, bh in blocks:
        d.rectangle([x, y, x + w, y + bh], fill=WHITE)
        
    # 绘制圆圈表面
    d.ellipse([j_dot_cx - j_dot_r, j_dot_cy - j_dot_r,
               j_dot_cx + j_dot_r, j_dot_cy + j_dot_r], fill=WHITE)

    # === 绘制高级液态玻璃立体三角形 ===
    tri_h = j_dot_r * 0.95
    tri_w = tri_h * 0.9
    
    # 完美居中修正：将质心（视觉重心）调整到圆心位置
    left_x = j_dot_cx - tri_w / 3
    right_x = j_dot_cx + 2 * tri_w / 3
    top_y = j_dot_cy - tri_h / 2
    bottom_y = j_dot_cy + tri_h / 2

    # 1. 极小投影（3D浮起感）
    proj_offset = int(s * 0.04)
    proj_layer = Image.new("RGBA", (size, size), HOLE)
    pd = ImageDraw.Draw(proj_layer)
    pd.polygon([(left_x + proj_offset, top_y + proj_offset), 
                (left_x + proj_offset, bottom_y + proj_offset), 
                (right_x + proj_offset, j_dot_cy + proj_offset)], fill=(0, 0, 0, 80))
    proj_layer = proj_layer.filter(ImageFilter.GaussianBlur(radius=thick * 0.2))
    layer.alpha_composite(proj_layer)

    # 2. 制作圆角蒙版（利用轻微高斯模糊实现液滴圆角）
    tri_mask = Image.new("L", (size, size), 0)
    td = ImageDraw.Draw(tri_mask)
    td.polygon([(left_x, top_y), (left_x, bottom_y), (right_x, j_dot_cy)], fill=255)
    tri_mask = tri_mask.filter(ImageFilter.GaussianBlur(radius=1.8))  # 超采样4倍，实际圆角很理想

    # 3. 内发光（边缘光晕 - 呼应流光背景）
    glow_layer = Image.new("RGBA", (size, size), HOLE)
    gd = ImageDraw.Draw(glow_layer)
    expand = int(s * 0.06)
    gd.polygon([(left_x - expand, top_y - expand), 
                (left_x - expand, bottom_y + expand), 
                (right_x + expand, j_dot_cy)], fill=(*GRAD_B, 100))
    glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(radius=thick * 0.3))
    layer.alpha_composite(glow_layer)

    # 4. 绘制受光渐变（模拟液滴/玻璃宝石质感，左上受光泛白，右下深紫）
    grad_layer = Image.new("RGBA", (size, size), HOLE)
    gd = ImageDraw.Draw(grad_layer)
    for i in range(size * 2):
        t = i / (size * 2)
        # 左上 -> 右下渐变
        if t < 0.5:
            t2 = t * 2
            r = int(255 + (GRAD_A[0] - 255) * t2)
            g = int(255 + (GRAD_A[1] - 255) * t2)
            b = int(255 + (GRAD_A[2] - 255) * t2)
        else:
            t2 = (t - 0.5) * 2
            r = int(GRAD_A[0] + (GRAD_MID[0] - GRAD_A[0]) * t2)
            g = int(GRAD_A[1] + (GRAD_MID[1] - GRAD_A[1]) * t2)
            b = int(GRAD_A[2] + (GRAD_MID[2] - GRAD_A[2]) * t2)
        gd.line([(i, 0), (0, i)], fill=(r, g, b, 255))

    # 利用蒙版将渐变抠入
    layer.paste(grad_layer, (0, 0), tri_mask)

    # 5. 边缘高光（锐利水晶切割感）
    hd = ImageDraw.Draw(layer)
    hd.line([(left_x + 2, top_y + 2), (left_x + 2, bottom_y - 2)], fill=(255, 255, 255, 200), width=int(s * 0.04))
    hd.line([(left_x + 2, top_y + 2), (right_x - 2, j_dot_cy)], fill=(255, 255, 255, 200), width=int(s * 0.04))

    # === 添加内侧高光与阴影（提升立体浮雕质感）===
    highlight = Image.new("RGBA", (size, size), HOLE)
    hd = ImageDraw.Draw(highlight)
    
    # T和J的立体高光（顶部和左侧亮线）
    for x, y, w, bh in blocks:
        hd.line([(x + 1, y + 1), (x + w - 1, y + 1)], fill=(255, 255, 255, 210), width=int(s * 0.08))
        hd.line([(x + 1, y + 1), (x + 1, y + bh - 1)], fill=(255, 255, 255, 210), width=int(s * 0.08))
    
    # 圆圈高光（顶部和左侧弧线）
    hd.arc([j_dot_cx - j_dot_r, j_dot_cy - j_dot_r, j_dot_cx + j_dot_r, j_dot_cy + j_dot_r], start=180, end=270, fill=(255, 255, 255, 210), width=int(s * 0.08))
    
    layer.alpha_composite(highlight)

    # 如果是 iPhone17 风格，增加发光效果（OLED光晕）
    if style == "iphone17":
        glow_layer = Image.new("RGBA", (size, size), HOLE)
        gd = ImageDraw.Draw(glow_layer)
        # 在字母下方增加光斑
        gd.ellipse([j_dot_cx - j_dot_r*1.5, j_dot_cy - j_dot_r*1.5,
                    j_dot_cx + j_dot_r*1.5, j_dot_cy + j_dot_r*1.5], fill=(*GRAD_B, 60))
        glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(radius=s * 0.3))
        layer.alpha_composite(glow_layer)

    return layer


def draw_badge(size):
    """只画 J 的立体浮雕轮廓（用于小尺寸降级），尺寸保持与主图一致。"""
    layer = Image.new("RGBA", (size, size), HOLE)
    d = ImageDraw.Draw(layer)
    s = size * 0.32
    h = size * 0.8
    x = (size - s) / 2
    y = (size - h) / 2
    
    # 计算圆点：顶部与整体顶边对齐
    j_dot_r = s * 0.57
    j_dot_cx = x + s / 2
    j_dot_cy = y + j_dot_r
    
    # 钩子（向左延伸，底部对齐）
    hook_w = s * 0.9
    hook_h = s * 0.55
    hook_x = x - hook_w          
    hook_y = y + h - hook_h
    
    # === 精确物理包围盒居中修正 ===
    real_left = min(x, hook_x)
    real_right = x + s
    real_w = real_right - real_left
    shift_x = (size - real_w) / 2 - real_left
    x += shift_x
    hook_x += shift_x
    j_dot_cx += shift_x

    # 微厚度挤出
    d.rectangle([x + 2, y + 2, x + s + 2, y + h + 2], fill=(120, 50, 140, 120))
    d.ellipse([j_dot_cx - j_dot_r + 2, j_dot_cy - j_dot_r + 2,
               j_dot_cx + j_dot_r + 2, j_dot_cy + j_dot_r + 2], fill=(120, 50, 140, 120))

    d.rectangle([x, y, x + s, y + h], fill=WHITE)
    d.ellipse([j_dot_cx - j_dot_r, j_dot_cy - j_dot_r,
               j_dot_cx + j_dot_r, j_dot_cy + j_dot_r], fill=WHITE)
    
    # 钩子
    d.rectangle([hook_x, hook_y, hook_x + hook_w, hook_y + hook_h], fill=WHITE)
    return layer


# --- 位图绘制（流光溢彩渐变）---
def _smoothstep(t):
    """平滑插值函数，让渐变更加柔和灵动。"""
    return t * t * (3 - 2 * t)

def make_gradient(size, inset=0.0):
    """四重色彩平滑对角线性渐变，带有流光质感。"""
    grad = Image.new("RGB", (size, size))
    px = grad.load()
    denom = 2.0 * (size - 1)
    span = max(1e-6, 1.0 - 2.0 * inset)
    
    for y in range(size):
        for x in range(size):
            t = (x + y) / denom
            t = min(1.0, max(0.0, (t - inset) / span))
            t = _smoothstep(t)
            
            # 四段色彩插值 (紫 -> 青 -> 洋红 -> 粉)
            if t < 0.33:
                t2 = t / 0.33
                r = round(GRAD_A[0] + (GRAD_C[0] - GRAD_A[0]) * t2)
                g = round(GRAD_A[1] + (GRAD_C[1] - GRAD_A[1]) * t2)
                b = round(GRAD_A[2] + (GRAD_C[2] - GRAD_A[2]) * t2)
            elif t < 0.66:
                t2 = (t - 0.33) / 0.33
                r = round(GRAD_C[0] + (GRAD_MID[0] - GRAD_C[0]) * t2)
                g = round(GRAD_C[1] + (GRAD_MID[1] - GRAD_C[1]) * t2)
                b = round(GRAD_C[2] + (GRAD_MID[2] - GRAD_C[2]) * t2)
            else:
                t2 = (t - 0.66) / 0.34
                r = round(GRAD_MID[0] + (GRAD_B[0] - GRAD_MID[0]) * t2)
                g = round(GRAD_MID[1] + (GRAD_B[1] - GRAD_MID[1]) * t2)
                b = round(GRAD_MID[2] + (GRAD_B[2] - GRAD_MID[2]) * t2)
            
            px[x, y] = (r, g, b)
            
    return grad.convert("RGBA")


def add_iphone17_background_flare(img):
    """在背景中心增加柔和的径向光斑。"""
    overlay = Image.new("RGBA", img.size, HOLE)
    d = ImageDraw.Draw(overlay)
    w, h = img.size
    center_x, center_y = w * 0.4, h * 0.4
    radius = w * 0.4
    d.ellipse([center_x - radius, center_y - radius, 
               center_x + radius, center_y + radius], fill=(255, 255, 255, 35))
    overlay = overlay.filter(ImageFilter.GaussianBlur(radius=w * 0.1))
    return Image.alpha_composite(img, overlay)


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
           inset=None, badge=None, style="3d"):
    """渲染完整图标。
    
    style: 控制 `draw_wordmark` 的立体风格（仅支持 3d 和 iphone17）。
    """
    if inset is None:
        inset = {"circle": GRAD_INSET_CIRCLE,
                 "rounded": GRAD_INSET_ROUNDED}.get(shape, GRAD_INSET_SQUARE)
    if badge is None:
        badge = size < WORDMARK_MIN_PX
    big = size * SS
    
    # 绘制流光渐变背景
    img = make_gradient(big, inset)
    img = add_iphone17_background_flare(img)
    
    # 绘制主体字标（始终使用立体浮雕）
    if badge:
        img.alpha_composite(draw_badge(big))
    else:
        img.alpha_composite(draw_wordmark(big, fill, style=style))
    
    # 外形裁切
    if shape != "square":
        img.putalpha(_mask(big, shape, radius_ratio))
    return img.resize((size, size), Image.LANCZOS)


def render_banner(w, h, style="3d"):
    """Android TV banner 320x180。"""
    bw, bh = w * SS, h * SS
    img = make_gradient(max(bw, bh)).resize((bw, bh), Image.LANCZOS)
    img = add_iphone17_background_flare(img)
    
    mark_box = int(bh * 0.62)
    mark = draw_wordmark(mark_box, 0.92, style=style)
    img.alpha_composite(mark, (int(bw * 0.075), int((bh - mark_box) / 2)))
        
    return img.resize((w, h), Image.LANCZOS)


def render_notification(size):
    """通知栏小图标：纯白扁平轮廓（系统强制要求纯白透明，必须保持扁平）。"""
    return draw_wordmark(size * SS, FILL_NOTIFY, style="3d").resize((size, size),
                                                        Image.LANCZOS)


# --- VectorDrawable 生成 ---
def _p(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def wordmark_paths(fill):
    """返回 (顶面路径, 空, 空, 三角形路径)，坐标基于 512 视口。"""
    x0, y0, h = layout(VIEWPORT, fill)
    s = h * R_STEM

    t_bar_w = h * R_T_W
    t_bar_h = s
    t_stem_x = x0 + (t_bar_w - s) / 2
    t_stem_y = y0 + s
    t_stem_h = h - s

    j_x = x0 + h * (R_T_W + R_GAP)
    j_stem_w = h * 0.32

    # 计算圆点：顶部与 T 平齐
    j_dot_r = j_stem_w * 0.57
    j_dot_cx = j_x + j_stem_w / 2
    j_dot_cy = y0 + j_dot_r

    # J 竖干缩短，底部对齐
    gap = s * 0.15
    j_stem_y = j_dot_cy + j_dot_r + gap
    j_stem_h = (y0 + h) - j_stem_y

    # 钩子
    hook_w = j_stem_w * 0.9
    hook_h = j_stem_w * 0.55
    hook_x = j_x - hook_w
    hook_y = y0 + h - hook_h

    # === 精确物理包围盒居中修正 ===
    real_left = min(x0, hook_x)
    real_right = max(x0 + t_bar_w, j_x + j_stem_w)
    real_w = real_right - real_left
    shift_x = (VIEWPORT - real_w) / 2 - real_left

    x0 += shift_x
    t_stem_x += shift_x
    j_x += shift_x
    hook_x += shift_x
    j_dot_cx += shift_x

    blocks = [
        (x0, y0, t_bar_w, t_bar_h),
        (t_stem_x, t_stem_y, s, t_stem_h),
        (j_x, j_stem_y, j_stem_w, j_stem_h),
        (hook_x, hook_y, hook_w, hook_h),
    ]

    def rect_to_path(x, y, w, bh):
        return f"M{_p(x)},{_p(y)}L{_p(x+w)},{_p(y)}L{_p(x+w)},{_p(y+bh)}L{_p(x)},{_p(y+bh)}z"

    if fill == FILL_NOTIFY:
        paths = [rect_to_path(*b) for b in blocks]
        return "".join(paths), "", "", ""

    top_paths = []
    for x, y, w, bh in blocks:
        top_paths.append(rect_to_path(x, y, w, bh))

    circle_path = (f"M{_p(j_dot_cx)},{_p(j_dot_cy - j_dot_r)}"
                   f"A{_p(j_dot_r)},{_p(j_dot_r)} 0 1 1 {_p(j_dot_cx)},{_p(j_dot_cy + j_dot_r)}"
                   f"A{_p(j_dot_r)},{_p(j_dot_r)} 0 1 1 {_p(j_dot_cx)},{_p(j_dot_cy - j_dot_r)}z")
    top_paths.append(circle_path)

    # 三角形（调整至质心完美居中，无偏移）
    tri_h = j_dot_r * 0.95
    tri_w = tri_h * 0.9
    tri_path = (f"M{_p(j_dot_cx - tri_w/3)},{_p(j_dot_cy - tri_h/2)}"
                f"L{_p(j_dot_cx - tri_w/3)},{_p(j_dot_cy + tri_h/2)}"
                f"L{_p(j_dot_cx + 2*tri_w/3)},{_p(j_dot_cy)}z")

    return "".join(top_paths), "", "", tri_path


def vector_wordmark(fill, color="#FFFFFF", size_dp=108):
    """生成字标的 VectorDrawable。由于 Android 矢量图限制，仅输出基础形状。"""
    top, _, _, tri = wordmark_paths(fill)
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
    <!-- 紫色播放三角（向右） -->
    <path
        android:fillColor="{GRAD_A_HEX}"
        android:pathData="{tri}" />
</vector>
"""


def vector_background():
    """自适应图标背景：流光溢彩的对角渐变。"""
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
                <item android:offset="0.33" android:color="#5CBFE0" />
                <item android:offset="0.66" android:color="#F09EE0" />
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


# --- 输出清单 ---
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


def do_preview(style="3d"):
    out = "build/icon-preview"
    print(f"[preview] -> {out}/ (Style: {style})")
    save_img(render(512, "rounded", style=style), f"{out}/rounded_512.png")
    save_img(render(512, "circle", fill=FILL_CIRCLE, style=style), f"{out}/circle_512.png")
    save_img(render(512, "square", style=style), f"{out}/square_512.png")
    save_img(render(48, "rounded", style=style), f"{out}/rounded_48.png")
    save_img(render(72, "rounded", style=style), f"{out}/rounded_72.png")
    save_img(render(96, "rounded", style=style), f"{out}/rounded_96.png")
    save_img(render_banner(320, 180, style), f"{out}/banner.png")
    save_img(render(432, "circle", fill=FILL_SAFE, style=style), f"{out}/adaptive_circle.png")
    save_img(render(432, "rounded", fill=FILL_SAFE, radius_ratio=0.30, style=style),
             f"{out}/adaptive_squircle.png")
    mono = Image.new("RGBA", (432, 432), (0x1F, 0x1F, 0x1F, 255))
    mono.alpha_composite(draw_wordmark(432, FILL_SAFE, style="3d"))
    save_img(mono, f"{out}/monochrome.png")
    save_img(render(LOGO_PX, "circle", fill=FILL_CIRCLE, style=style), f"{out}/logo.png")
    for px in FAVICON_SIZES:
        save_img(render(px, "circle", fill=FILL_CIRCLE, style=style).resize(
            (px * 8, px * 8), Image.NEAREST), f"{out}/favicon_{px}.png")
    for px in (24, 36, 48, 72):
        bar = Image.new("RGBA", (px, px), (0x20, 0x21, 0x24, 255))
        bar.alpha_composite(render_notification(px))
        save_img(bar.resize((px * 8, px * 8), Image.NEAREST),
                 f"{out}/notification_{px}.png")


def do_write(style="3d"):
    print(f"[writing resources] (Style: {style})")
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
        save_img(render(px, "rounded", style=style), f"{MAIN_RES}/mipmap-{name}/ic_launcher.png",
                 format="PNG")
        save_img(render(base, "circle", fill=FILL_CIRCLE, style=style),
                 f"{MAIN_RES}/mipmap-{name}/ic_launcher_round.webp",
                 format="WEBP", lossless=True, quality=100)

    print("[play store]")
    save_img(render(512, "square", style=style), "app/src/main/ic_launcher-playstore.png",
             format="PNG")

    print("[tv banner]")
    save_text(vector_wordmark(FILL_SAFE),
              "app/src/leanback/res/drawable/ic_banner_foreground.xml")
    save_text(BANNER_XML, "app/src/leanback/res/mipmap-anydpi-v26/ic_banner.xml")
    save_img(render_banner(320, 180, style), "app/src/leanback/res/drawable/ic_banner.png",
             format="PNG")

    print("[in-app logo]")
    save_img(render(LOGO_PX, "circle", fill=FILL_CIRCLE, style=style),
             f"{MAIN_RES}/drawable-nodpi/ic_logo.png", format="PNG")

    print("[notification]")
    save_text(vector_wordmark(FILL_NOTIFY, size_dp=24),
              f"{MAIN_RES}/drawable-anydpi/ic_notification.xml")
    for name, px in NOTIFY_DENSITIES:
        save_img(render_notification(px),
                 f"{MAIN_RES}/drawable-{name}/ic_notification.png", format="PNG")

    print("[web favicon]")
    sizes = sorted(FAVICON_SIZES)
    frames = [render(px, "circle", fill=FILL_CIRCLE, style=style) for px in sizes]
    save_img(frames[-1], "app/src/main/assets/favicon.ico", format="ICO",
             sizes=[(px, px) for px in sizes],
             append_images=frames[:-1])
    print("\nDone.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true",
                    help="只输出预览图到 build/icon-preview/，不改 res/")
    ap.add_argument("--style", type=str, default="3d", choices=["3d", "iphone17"],
                    help="选择图标渲染风格 (默认: 3d，不支持 flat)")
    args = ap.parse_args()
    
    do_preview(args.style) if args.preview else do_write(args.style)


if __name__ == "__main__":
    main()