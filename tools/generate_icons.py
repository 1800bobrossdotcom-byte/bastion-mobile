"""
BASTION icon generator.
- 512x512 Play Store icon.
- Android mipmap set (mdpi..xxxhdpi) for ic_launcher + ic_launcher_round.
- Adaptive icon foreground (432x432 PNG) with safe zone respected.
Aesthetic: phosphor-green bracketed "B" wordmark on near-black surface.
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
STORE = os.path.join(ROOT, "store", "play", "assets")
os.makedirs(STORE, exist_ok=True)

PHOSPHOR = (51, 255, 102, 255)
PHOSPHOR_DIM = (51, 255, 102, 90)
SURFACE = (10, 10, 10, 255)
BORDER = (31, 31, 31, 255)

FONT_CANDIDATES = [
    "C:/Windows/Fonts/consolab.ttf",
    "C:/Windows/Fonts/consola.ttf",
    "C:/Windows/Fonts/cour.ttf",
]

def load_font(size):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

def draw_mark(img, glyph_color=PHOSPHOR, bg=SURFACE, square=True, bracket=True):
    w, h = img.size
    d = ImageDraw.Draw(img)
    if bg is not None:
        if square:
            d.rectangle([0, 0, w, h], fill=bg)
        else:
            d.ellipse([0, 0, w, h], fill=bg)
    # subtle CRT scanlines
    for y in range(0, h, max(2, h // 96)):
        d.line([(0, y), (w, y)], fill=(0, 0, 0, 25))
    # bracketed B mark, centered, occupies ~60% of canvas (safe zone)
    text = "[B]" if bracket else "B"
    # find best font size to fit ~62% width
    target_w = int(w * 0.62)
    size = int(h * 0.55)
    font = load_font(size)
    while size > 8:
        font = load_font(size)
        bbox = d.textbbox((0, 0), text, font=font)
        if (bbox[2] - bbox[0]) <= target_w and (bbox[3] - bbox[1]) <= int(h * 0.6):
            break
        size -= 2
    bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (w - tw) // 2 - bbox[0]
    y = (h - th) // 2 - bbox[1]
    # glow
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.text((x, y), text, font=font, fill=(51, 255, 102, 140))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=max(2, h // 60)))
    img.alpha_composite(glow)
    d.text((x, y), text, font=font, fill=glyph_color)
    # corner ticks
    tick = max(2, w // 96)
    arm = max(8, w // 16)
    for (cx, cy) in [(arm, arm), (w - arm, arm), (arm, h - arm), (w - arm, h - arm)]:
        d.line([(cx - arm // 2, cy), (cx + arm // 2, cy)], fill=PHOSPHOR_DIM, width=tick)
        d.line([(cx, cy - arm // 2), (cx, cy + arm // 2)], fill=PHOSPHOR_DIM, width=tick)
    return img

def make_square(size, bg=SURFACE):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return draw_mark(img, bg=bg, square=True)

def make_round(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return draw_mark(img, bg=SURFACE, square=False)

def make_adaptive_foreground(size=432):
    # transparent bg + glyph fits 264x264 safe zone (centered)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # don't draw bg or scanlines on foreground (background layer handles it)
    text = "[B]"
    target_w = int(size * 0.55)
    s = int(size * 0.45)
    font = load_font(s)
    while s > 8:
        font = load_font(s)
        bbox = d.textbbox((0, 0), text, font=font)
        if (bbox[2] - bbox[0]) <= target_w and (bbox[3] - bbox[1]) <= int(size * 0.5):
            break
        s -= 2
    bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (size - tw) // 2 - bbox[0]
    y = (size - th) // 2 - bbox[1]
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.text((x, y), text, font=font, fill=(51, 255, 102, 160))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=size // 50))
    img.alpha_composite(glow)
    d.text((x, y), text, font=font, fill=PHOSPHOR)
    return img

def make_adaptive_background(size=432):
    img = Image.new("RGBA", (size, size), SURFACE)
    d = ImageDraw.Draw(img)
    for y in range(0, size, max(2, size // 80)):
        d.line([(0, y), (size, y)], fill=(0, 0, 0, 20))
    # subtle border ring tint
    d.rectangle([0, 0, size - 1, size - 1], outline=(31, 31, 31, 255), width=max(1, size // 96))
    return img

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

def write_mipmaps():
    for d, sz in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{d}")
        os.makedirs(folder, exist_ok=True)
        make_square(sz).save(os.path.join(folder, "ic_launcher.png"))
        make_round(sz).save(os.path.join(folder, "ic_launcher_round.png"))
        # adaptive foreground/background scaled per density too
        # base 108dp -> px = sz * (108/48)
        adp = int(sz * 108 / 48)
        make_adaptive_foreground(adp).save(os.path.join(folder, "ic_launcher_foreground.png"))
        make_adaptive_background(adp).save(os.path.join(folder, "ic_launcher_background.png"))

def write_play_store():
    make_square(512).save(os.path.join(STORE, "icon-512.png"))
    # also a large 1024
    make_square(1024).save(os.path.join(STORE, "icon-1024.png"))

def write_adaptive_xml():
    folder = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(folder, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    with open(os.path.join(folder, "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write(xml)
    with open(os.path.join(folder, "ic_launcher_round.xml"), "w", encoding="utf-8") as f:
        f.write(xml)

if __name__ == "__main__":
    write_mipmaps()
    write_adaptive_xml()
    write_play_store()
    print("icons written to", RES)
    print("play store icons written to", STORE)
