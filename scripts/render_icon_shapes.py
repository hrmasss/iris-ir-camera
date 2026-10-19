"""Render the adaptive icon under common launcher masks: python3 scripts/render_icon_shapes.py app/src/main/res docs/icon-shapes.png /tmp/icon-work"""
import math
import pathlib
import re
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

res = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
work = pathlib.Path(sys.argv[3])
S = 8  # px per dp
FULL = 108 * S

fg = (res / "drawable/ic_launcher_foreground.xml").read_text()
mono = (res / "drawable/ic_launcher_monochrome.xml").read_text()


def paths(xml):
    """Attributes of each <path> in a VectorDrawable."""
    for m in re.finditer(r"<path(.*?)(/>|>.*?</path>)", xml, re.S):
        yield dict(re.findall(r'android:(\w+)="([^"]*)"', m.group(1)))


def group_scale(xml):
    m = re.search(r'android:scaleX="([\d.]+)"', xml)
    return float(m.group(1)) if m else 1.0


SCALE = group_scale(fg)
assert SCALE == group_scale(mono), "foreground and monochrome must share a scale"


def svg(body, extra="", scaled=True):
    if scaled and SCALE != 1.0:
        body = f'<g transform="translate(54 54) scale({SCALE}) translate(-54 -54)">{body}</g>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{FULL}" height="{FULL}" '
            f'viewBox="0 0 108 108">{extra}{body}</svg>')


def color(c):
    return "#" + c[3:] if len(c) == 9 else c


def path_el(a, fill=None, stroke=None):
    f = color(fill if fill is not None else a.get("fillColor", "none"))
    if f in ("#000000",) and a.get("fillColor", "").startswith("#00"):
        f = "none"
    s = ""
    if stroke or "strokeColor" in a:
        s = (f' stroke="{color(stroke or a["strokeColor"])}" stroke-width="{a.get("strokeWidth", "1")}"'
             ' stroke-linejoin="round"')
    op = f' fill-opacity="{a["fillAlpha"]}"' if "fillAlpha" in a else ""
    rule = ' fill-rule="evenodd"' if a.get("fillType") == "evenOdd" else ""
    return f'<path d="{a["pathData"]}" fill="{f}"{op}{rule}{s}/>'


def raster(text, name):
    p = work / f"{name}.svg"
    p.write_text(text)
    png = work / f"{name}.png"
    subprocess.run(["rsvg-convert", "-w", str(FULL), "-h", str(FULL), "-o", str(png), str(p)], check=True)
    return Image.open(png).convert("RGBA")


fg_paths = list(paths(fg))
# The iris is the only path filled by a gradient, so it has neither fillColor nor strokeColor.
iris = [a for a in fg_paths if "fillColor" not in a and "strokeColor" not in a][0]
rest = [a for a in fg_paths if a is not iris]

bg = raster(svg('<rect width="108" height="108" fill="url(#g)"/>',
                '<defs><radialGradient id="g" gradientUnits="userSpaceOnUse" cx="54" cy="48" r="76">'
                '<stop offset="0" stop-color="#2E1A57"/><stop offset="1" stop-color="#120A24"/>'
                '</radialGradient></defs>', scaled=False), "bg")

# SVG has no sweep gradient, so draw it per pixel and clip it with the iris path.
iris_mask = raster(svg(path_el(iris, fill="#FFFFFF")), "iris_mask").split()[3]
stops = [(0, (0xFF, 0x5A, 0x3C)), (0.33, (0xFF, 0x2D, 0x87)), (0.66, (0xB2, 0x3C, 0xFF)), (1, (0xFF, 0x5A, 0x3C))]
sweep = Image.new("RGBA", (FULL, FULL))
px = sweep.load()
c = 54 * S
for y in range(30 * S, 78 * S):
    for x in range(30 * S, 78 * S):
        t = (math.atan2(y - c, x - c) / (2 * math.pi)) % 1.0
        for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
            if t <= t1:
                f = (t - t0) / (t1 - t0)
                px[x, y] = tuple(int(c0[i] + (c1[i] - c0[i]) * f) for i in range(3)) + (255,)
                break
iris_layer = Image.new("RGBA", (FULL, FULL))
iris_layer.paste(sweep, (0, 0), iris_mask)
top = raster(svg("".join(path_el(a) for a in rest)), "fg_top")
icon = Image.alpha_composite(Image.alpha_composite(bg, iris_layer), top)

# Themed icons: the monochrome layer tinted dark on a pale background.
ink = "#3B2E58"
themed_fg = raster(svg("".join(
    path_el(a, fill=ink if a.get("fillColor", "").upper() not in ("#00000000",) else None,
            stroke=ink if "strokeColor" in a else None)
    for a in paths(mono))), "mono")
themed = Image.alpha_composite(Image.new("RGBA", (FULL, FULL), (0xE9, 0xDD, 0xFF, 255)), themed_fg)

N = 72 * S
R = N / 2


def crop72(img):
    a = 18 * S
    return img.crop((a, a, FULL - a, FULL - a))


def polar_mask(fn, steps=1440):
    m = Image.new("L", (N, N), 0)
    pts = [(R + fn(t) * math.cos(t), R + fn(t) * math.sin(t))
           for t in (2 * math.pi * i / steps for i in range(steps))]
    ImageDraw.Draw(m).polygon(pts, fill=255)
    return m


def squircle(t, n=4.0):
    return R / ((abs(math.cos(t)) ** n + abs(math.sin(t)) ** n) ** (1 / n))


def cookie(lobes, depth):
    return lambda t: R * (1 - depth + depth * (0.5 + 0.5 * math.cos(lobes * t)))


shapes = {
    "Circle": polar_mask(lambda t: R),
    "Squircle": polar_mask(squircle),
    "Cookie (9)": polar_mask(cookie(9, 0.10)),
    "Cookie (12)": polar_mask(cookie(12, 0.07)),
    "Clover": polar_mask(lambda t: R * (0.74 + 0.26 * abs(math.cos(2 * t)) ** 0.5)),
}
m = Image.new("L", (N, N), 0)
ImageDraw.Draw(m).rounded_rectangle((0, 0, N - 1, N - 1), radius=16 * S, fill=255)
shapes["Rounded square"] = m
m = Image.new("L", (N, N), 0)
d = ImageDraw.Draw(m)
d.ellipse((0, 0, N - 1, N - 1), fill=255)
d.rectangle((0, N // 2, N - 1, N - 1 - 8 * S), fill=255)
d.rounded_rectangle((0, N // 2 + 8 * S, N - 1, N - 1), radius=8 * S, fill=255)
shapes["Arch"] = m
m = Image.new("L", (N, N), 0)
d = ImageDraw.Draw(m)
d.ellipse((0, 0, N - 1, N - 1), fill=255)
d.rectangle((N // 2, N // 2, N - 1, N - 1 - 6 * S), fill=255)
d.rectangle((N // 2, N // 2, N - 1 - 6 * S, N - 1), fill=255)
d.rounded_rectangle((N // 2, N // 2, N - 1, N - 1), radius=6 * S, fill=255)
shapes["Teardrop"] = m

cell, pad, label_h = 200, 28, 44
cols = 4
rows_per_set = math.ceil(len(shapes) / cols)
W = pad + cols * (cell + pad)
H = pad + (2 * rows_per_set + 1) * (cell + label_h) + pad
sheet = Image.new("RGBA", (W, H), (24, 22, 30, 255))
d = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 17)
except OSError:
    font = ImageFont.load_default()


def place(img, mask, col, row, label, full=False):
    x = pad + col * (cell + pad)
    y = pad + row * (cell + label_h)
    tile = (img if full else crop72(img)).resize((cell, cell), Image.LANCZOS)
    sheet.paste(tile, (x, y), mask.resize((cell, cell), Image.LANCZOS) if mask is not None else None)
    d.text((x + cell / 2, y + cell + 10), label, fill=(220, 214, 230), font=font, anchor="ma")


for i, (name, mk) in enumerate(shapes.items()):
    place(icon, mk, i % cols, i // cols, name)
    place(themed, mk, i % cols, rows_per_set + i // cols, name + ", themed")

safe = icon.copy()
d2 = ImageDraw.Draw(safe)
d2.ellipse((21 * S, 21 * S, 87 * S, 87 * S), outline=(0, 230, 150, 255), width=6)
d2.rectangle((18 * S, 18 * S, 90 * S, 90 * S), outline=(255, 255, 255, 150), width=4)
place(safe, None, 0, 2 * rows_per_set, "108 dp canvas", full=True)
d.multiline_text((pad * 2 + cell, pad + 2 * rows_per_set * (cell + label_h) + 60),
                 "Green circle: 66 dp safe zone, never masked.\nWhite square: the 72 dp area a launcher shows.",
                 fill=(220, 214, 230), font=font, spacing=10)
sheet.save(out)

alpha = Image.alpha_composite(iris_layer, top).split()[3].load()
far = max(math.hypot(x - c, y - c) for y in range(0, FULL, 2) for x in range(0, FULL, 2) if alpha[x, y] > 16)
print(f"foreground reaches {far / S:.1f} dp from the centre; safe zone radius is 33 dp")
