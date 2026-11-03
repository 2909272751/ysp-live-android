#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成央视频直播的启动图标与 TV banner。

无需手工准备图片：本脚本用 Pillow 画出“圆角红底 + 白色播放三角 + 央字”的图标，
并写入 res/mipmap-*/ic_launcher.png 与 res/drawable-xhdpi/banner.png。

用法：
    python tools/gen_launcher_icon.py
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 各密度下的 launcher 边长（48dp）
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]

RED_TOP = (230, 47, 60)
RED_BOTTOM = (176, 22, 36)
WHITE = (255, 255, 255, 255)


def load_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def vertical_gradient(size, top, bottom):
    """生成竖向渐变底图。"""
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = (
            int(top[0] + (bottom[0] - top[0]) * t),
            int(top[1] + (bottom[1] - top[1]) * t),
            int(top[2] + (bottom[2] - top[2]) * t),
        )
    return img.resize((w, h), Image.BILINEAR).convert("RGBA")


def draw_play_triangle(draw, cx, cy, size, color):
    """画一个向右的播放三角。"""
    half = size / 2.0
    draw.polygon(
        [
            (cx - half * 0.72, cy - half),
            (cx - half * 0.72, cy + half),
            (cx + half, cy),
        ],
        fill=color,
    )


def rounded_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def fit_font(draw, text, max_width, max_height, start):
    """从 start 开始逐级缩小字号，直到文本能放进给定框内。"""
    size = start
    while size > 6:
        font = load_font(size)
        box = draw.textbbox((0, 0), text, font=font)
        if (box[2] - box[0]) <= max_width and (box[3] - box[1]) <= max_height:
            return font, box
        size = int(size * 0.92)
    font = load_font(6)
    return font, draw.textbbox((0, 0), text, font=font)


def centered_text(draw, cx, cy, text, font, box, fill):
    """把文本按视觉中心画到 (cx, cy)。"""
    tw, th = box[2] - box[0], box[3] - box[1]
    draw.text((cx - tw / 2.0 - box[0], cy - th / 2.0 - box[1]), text, font=font, fill=fill)


def make_icon(px):
    """生成正方形图标：红底 + 大“央”字 + 右下角播放三角。"""
    ss = 4  # 超采样，保证小尺寸下边缘干净
    big = px * ss
    base = vertical_gradient((big, big), RED_TOP, RED_BOTTOM)
    draw = ImageDraw.Draw(base)

    # 主字“央”占视觉中心略偏上
    font, box = fit_font(draw, "央", big * 0.52, big * 0.46, int(big * 0.60))
    centered_text(draw, big * 0.50, big * 0.44, "央", font, box, WHITE)

    # 右下角小播放三角作为“直播”暗示
    draw_play_triangle(draw, big * 0.71, big * 0.79, big * 0.115, (255, 255, 255, 255))

    # 圆角遮罩（自适应图标在 26+ 用矢量版本，这里给老系统与桌面用）
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(base, (0, 0), rounded_mask(big, radius=int(big * 0.22)))
    return out.resize((px, px), Image.LANCZOS)


def make_banner(width=320, height=180):
    """生成 TV banner：深色底 + 播放三角 + 央视频直播。"""
    ss = 2
    w, h = width * ss, height * ss
    base = vertical_gradient((w, h), (18, 23, 30), (8, 10, 14)).convert("RGBA")
    draw = ImageDraw.Draw(base)

    # 左侧红色圆角块 + 播放三角
    pad = int(h * 0.20)
    side = h - pad * 2
    box = [pad, pad, pad + side, h - pad]
    draw.rounded_rectangle(box, radius=int(h * 0.13), fill=RED_TOP + (255,))
    draw_play_triangle(draw, (box[0] + box[2]) / 2.0 + h * 0.03, h / 2.0, h * 0.17, WHITE)

    text_left = box[2] + int(h * 0.13)
    avail = w - text_left - int(h * 0.08)

    font, fb = fit_font(draw, "央视频直播", avail, h * 0.34, int(h * 0.30))
    draw.text((text_left - fb[0], h * 0.30 - fb[1]), "央视频直播", font=font,
              fill=(242, 244, 248, 255))

    font_small, sb = fit_font(draw, "64 路直播 · 本地代理", avail, h * 0.16, int(h * 0.14))
    draw.text((text_left - sb[0], h * 0.64 - sb[1]), "64 路直播 · 本地代理", font=font_small,
              fill=(150, 163, 180, 255))

    return base.resize((width, height), Image.LANCZOS)


def make_stat_icon(px):
    """生成通知栏小图标：透明底 + 纯白“央”字（系统会做单色化处理）。"""
    ss = 4
    big = px * ss
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    font, box = fit_font(draw, "央", big * 0.86, big * 0.86, int(big * 0.90))
    centered_text(draw, big * 0.50, big * 0.52, "央", font, box, (255, 255, 255, 255))
    return img.resize((px, px), Image.LANCZOS)


def main():
    written = []
    for density, px in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "ic_launcher.png")
        make_icon(px).save(path, "PNG", optimize=True)
        written.append(path)

    banner_dir = os.path.join(RES, "drawable-xhdpi")
    os.makedirs(banner_dir, exist_ok=True)
    banner_path = os.path.join(banner_dir, "banner.png")
    make_banner().save(banner_path, "PNG", optimize=True)
    written.append(banner_path)

    # 通知栏小图标（24dp，各密度）
    stat_sizes = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}
    for density, px in stat_sizes.items():
        folder = os.path.join(RES, "drawable-" + density)
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "ic_stat_live.png")
        make_stat_icon(px).save(path, "PNG", optimize=True)
        written.append(path)

    for path in written:
        print("%-72s %6d B" % (os.path.relpath(path, ROOT), os.path.getsize(path)))
    print("生成完成：%d 个文件" % len(written))
    return 0


if __name__ == "__main__":
    sys.exit(main())
