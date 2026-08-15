from PIL import Image
from pathlib import Path

src = Path(r"C:\Users\yurin\.cursor\projects\c-Users-yurin-Projects-Tubek\assets\tubek_icon.png")
res = Path(r"C:\Users\yurin\Projects\Tubek\app\src\main\res")
assets = Path(r"C:\Users\yurin\Projects\Tubek\assets")
assets.mkdir(parents=True, exist_ok=True)

img = Image.open(src).convert("RGBA")
red = (229, 57, 53, 255)  # #E53935

# Fill transparent corners with brand red for clean adaptive masking
bg = Image.new("RGBA", img.size, red)
composed = Image.alpha_composite(bg, img)
pixels = composed.load()
w, h = composed.size
for y in range(h):
    for x in range(w):
        r, g, b, a = pixels[x, y]
        if a < 250:
            pixels[x, y] = red

full = composed.convert("RGB").resize((1024, 1024), Image.Resampling.LANCZOS)
full_rgba = full.convert("RGBA")
full.save(assets / "ic_launcher_master.png", "PNG")
full.save(assets / "tubek_icon.png", "PNG")

mipmap_sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in mipmap_sizes.items():
    out_dir = res / folder
    out_dir.mkdir(parents=True, exist_ok=True)
    resized = full.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(out_dir / "ic_launcher.png", "PNG")
    resized.save(out_dir / "ic_launcher_round.png", "PNG")

# Adaptive foreground: keep white symbol on transparent
fg = Image.new("RGBA", full_rgba.size, (0, 0, 0, 0))
sp, dp = full_rgba.load(), fg.load()
fw, fh = full_rgba.size
for y in range(fh):
    for x in range(fw):
        r, g, b, a = sp[x, y]
        if r > 200 and g > 200 and b > 200:
            dp[x, y] = (255, 255, 255, 255)
        elif r > 170 and g > 150 and b > 150 and abs(r - g) < 45 and abs(g - b) < 45:
            alpha = max(0, min(255, int(((r + g + b) / 3 - 140) * 2.2)))
            if alpha > 0:
                dp[x, y] = (255, 255, 255, alpha)

canvas = 432
safe = int(canvas * 0.72)
symbol = fg.resize((safe, safe), Image.Resampling.LANCZOS)
layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
offset = (canvas - safe) // 2
layer.paste(symbol, (offset, offset), symbol)

fg_dirs = {
    "drawable-mdpi": 108,
    "drawable-hdpi": 162,
    "drawable-xhdpi": 216,
    "drawable-xxhdpi": 324,
    "drawable-xxxhdpi": 432,
}
for folder, size in fg_dirs.items():
    out_dir = res / folder
    out_dir.mkdir(parents=True, exist_ok=True)
    layer.resize((size, size), Image.Resampling.LANCZOS).save(
        out_dir / "ic_launcher_foreground.png", "PNG"
    )

(res / "drawable").mkdir(parents=True, exist_ok=True)
full.resize((512, 512), Image.Resampling.LANCZOS).save(res / "drawable" / "ic_launcher.png", "PNG")
print("icons written ok")
