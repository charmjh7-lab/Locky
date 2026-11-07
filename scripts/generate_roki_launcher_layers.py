#!/usr/bin/env python3
"""Generate ROKI's inset green background plus translucent door/L foreground.

The green rounded rectangle is the icon BACKGROUND. The outer area is
transparent, not a cream-colored image. The door and white L are the
independent foreground. Sizes account for the 72dp viewport of a 108dp layer.
"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts/assets/roki_full_bleed_source.webp"
DEST = ROOT / "app/src/main/res/drawable-nodpi"
SIZE = 432
PANEL_FRAC = 0.500   # 54dp in 108dp layer -> ~75% of 72dp viewport
SYMBOL_FRAC = 0.420  # 45.36dp -> separate mark with breathing room

source = np.asarray(Image.open(SOURCE).convert("RGB").resize((SIZE, SIZE))).copy()
r, g, b = (source[:, :, k].astype(np.int16) for k in range(3))

lime = (g > 118) & (g-r > 34) & (g-b > 45) & (r > 36)
white = (r > 142) & (g > 151) & (b > 139) & (abs(r-g) < 65) & (abs(g-b) < 70)

def keep_components(mask, threshold):
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype("uint8"), 8)
    result = np.zeros((SIZE, SIZE), "uint8")
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] > threshold:
            result[labels == i] = 255
    return result

lime_mask = keep_components(lime, 80)
lime_mask = cv2.morphologyEx(
    lime_mask, cv2.MORPH_CLOSE,
    cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (11, 11)))
white_mask = keep_components(white, 25)
door_alpha = cv2.GaussianBlur(
    cv2.dilate(lime_mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (33, 33))),
    (0, 0), 7.0)
letter_alpha = cv2.GaussianBlur(
    cv2.dilate(white_mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))),
    (0, 0), 0.75)
alpha = np.maximum(door_alpha, letter_alpha)

# Create a clean dark forest-green texture without the original baked-in
# doorway/letter: moving the baked image into the background leaves ghosts.
y, x = np.mgrid[0:SIZE, 0:SIZE].astype(np.float32)
xf, yf = x / (SIZE-1), y / (SIZE-1)
c00 = np.array([35, 91, 49], np.float32)
c10 = np.array([10, 60, 37], np.float32)
c01 = np.array([14, 61, 29], np.float32)
c11 = np.array([6, 45, 25], np.float32)
base = ((1-xf)*(1-yf))[:, :, None] * c00
base += (xf*(1-yf))[:, :, None] * c10
base += ((1-xf)*yf)[:, :, None] * c01
base += (xf*yf)[:, :, None] * c11
bloom = np.exp(-((xf-.18)**2/.19 + (yf-.17)**2/.27))
base += bloom[:, :, None] * np.array([4, 8, 2], np.float32)
patch = source[316:421, 278:423]
noise = cv2.cvtColor(patch, cv2.COLOR_RGB2GRAY).astype(np.float32)
noise -= cv2.GaussianBlur(noise, (0, 0), 8.0)
noise = cv2.resize(noise, (SIZE, SIZE), interpolation=cv2.INTER_CUBIC)
base += noise[:, :, None] * 0.23
background_rgb = np.uint8(np.clip(base, 0, 255))

# Green art stays behind the symbol, but is itself inset from the launcher
# mask. Transparent exterior reveals the user's wallpaper rather than being
# forced into a device-specific, full-size dark-green rounded square.
panel = round(SIZE * PANEL_FRAC)
origin = (SIZE-panel)//2
panel_mask = Image.new("L", (SIZE, SIZE), 0)
draw = ImageDraw.Draw(panel_mask)
draw.rounded_rectangle(
    (origin, origin, origin+panel-1, origin+panel-1),
    radius=round(panel*.22), fill=255)
panel_mask = panel_mask.filter(ImageFilter.GaussianBlur(.45))
background = Image.fromarray(background_rgb, "RGB").convert("RGBA")
background.putalpha(panel_mask)

# Foreground contains the luminous perspective door and white L only.
mark = Image.fromarray(np.dstack([source, alpha]), "RGBA")
mark_size = round(SIZE * SYMBOL_FRAC)
mark = mark.resize((mark_size, mark_size), Image.Resampling.LANCZOS)
pixels = np.asarray(mark).copy()
for row in range(round(mark_size*.87), mark_size):
    fade = max(0., (mark_size-1-row)/(mark_size*.13))
    green = ((pixels[row, :, 1].astype(np.int16) >
              pixels[row, :, 2].astype(np.int16)+15) &
             (pixels[row, :, 1] > pixels[row, :, 0]+15))
    pixels[row, green, 3] = np.uint8(
        pixels[row, green, 3].astype(np.float32)*fade)
mark = Image.fromarray(pixels, "RGBA")
foreground = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
foreground.alpha_composite(
    mark, ((SIZE-mark_size)//2, (SIZE-mark_size)//2))

DEST.mkdir(parents=True, exist_ok=True)
background.save(DEST/"roki_icon_background.png", optimize=True)
foreground.save(DEST/"roki_icon_foreground.png", optimize=True)
