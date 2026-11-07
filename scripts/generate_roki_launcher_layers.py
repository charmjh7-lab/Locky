#!/usr/bin/env python3
"""Generate two physically separate Android adaptive icon layers.

Green = full-size background; lime perspective door and white L = transparent
foreground with enough inset for Android launcher masks.
"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'scripts/assets/roki_full_bleed_source.webp'
DEST = ROOT / 'app/src/main/res/drawable-nodpi'
SIZE = 432
picture = np.asarray(Image.open(SOURCE).convert('RGB').resize((SIZE, SIZE))).copy()
r, g, b = [picture[:, :, c].astype(np.int16) for c in range(3)]

# Segment the door illumination and the white L, rather than including the
# outer green square in the foreground layer.
lime = (g > 118) & (g - r > 34) & (g - b > 45) & (r > 36)
white = (r > 142) & (g > 151) & (b > 139) & (abs(r - g) < 65) & (abs(g - b) < 70)

def remove_islands(binary, min_area):
    components, labels, stats, _ = cv2.connectedComponentsWithStats(
        binary.astype(np.uint8), connectivity=8
    )
    kept = np.zeros((SIZE, SIZE), dtype=np.uint8)
    for index in range(1, components):
        if stats[index, cv2.CC_STAT_AREA] >= min_area:
            kept[labels == index] = 255
    return kept

lime_mask = remove_islands(lime, 80)
lime_mask = cv2.morphologyEx(
    lime_mask, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (11, 11))
)
door_alpha = cv2.GaussianBlur(
    cv2.dilate(lime_mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (33, 33))),
    (0, 0), 7.0
)
letter_mask = remove_islands(white, 25)
letter_alpha = cv2.GaussianBlur(
    cv2.dilate(letter_mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))),
    (0, 0), 0.75
)
alpha = np.maximum(door_alpha, letter_alpha)

# Remove the logo marks from the green layer. Its original subtle texture and
# shading are preserved; no beige/cream screenshot backdrop is present.
inpaint_mask = cv2.dilate(
    np.maximum(lime_mask, letter_mask),
    cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (35, 35))
)
background = cv2.inpaint(picture, inpaint_mask, 12, cv2.INPAINT_TELEA)
soft = cv2.GaussianBlur(background, (0, 0), 3)
weight = cv2.GaussianBlur(inpaint_mask, (0, 0), 9).astype(np.float32)[:, :, None] / 255
background = (
    background.astype(np.float32) * (1 - 0.4 * weight)
    + soft.astype(np.float32) * (0.4 * weight)
).clip(0, 255).astype(np.uint8)

foreground = Image.fromarray(np.dstack((picture, alpha)), 'RGBA')
icon_size = round(SIZE * 0.73)
foreground = foreground.resize((icon_size, icon_size), Image.Resampling.LANCZOS)

# The original line runs off the bottom of the image. Fade the end instead of
# introducing an artificial horizontal cut when the foreground is inset.
pixels = np.array(foreground).copy()
for y in range(round(icon_size * 0.83), icon_size):
    fade = max(0.0, (icon_size - y - 1) / (icon_size * 0.17))
    colored = (
        (pixels[y, :, 1].astype(np.int16) > pixels[y, :, 2].astype(np.int16) + 15)
        & (pixels[y, :, 1] > pixels[y, :, 0] + 15)
    )
    pixels[y, colored, 3] = (pixels[y, colored, 3] * fade).astype(np.uint8)

canvas = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
canvas.alpha_composite(
    Image.fromarray(pixels, 'RGBA'),
    ((SIZE - icon_size) // 2, (SIZE - icon_size) // 2)
)

DEST.mkdir(parents=True, exist_ok=True)
Image.fromarray(background, 'RGB').save(
    DEST / 'roki_icon_background.webp', 'WEBP', quality=96, method=6
)
canvas.save(DEST / 'roki_icon_foreground.webp', 'WEBP', quality=96, method=6)
