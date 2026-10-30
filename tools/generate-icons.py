#!/usr/bin/env python3
"""Regenerate every branding asset from ``logos/logo.png``.

The brand logo is a transparent 1000x1000 PNG holding the "LK" monogram. Everything
Android needs is derived from it here, so the artwork stays the single source of
truth:

  app/src/main/res/mipmap-mdpi..xxxhdpi/ic_launcher.png            legacy icon
  app/src/main/res/mipmap-mdpi..xxxhdpi/ic_launcher_round.png      legacy round icon
  app/src/main/res/mipmap-mdpi..xxxhdpi/ic_launcher_foreground.png adaptive foreground
  app/src/main/res/mipmap-mdpi..xxxhdpi/ic_launcher_monochrome.png themed-icon layer
  app/src/main/res/drawable-nodpi/logo.png                         in-app / splash logo
  store-assets/ic_launcher_512.png                                 store listing icon
  store-assets/ic_launcher_round_512.png                           store listing icon (round)

Usage (Pillow is the only requirement):

    python tools/generate-icons.py

Geometry, in dp, because Android does the same at runtime:

  * legacy icon        48 x 48 dp canvas, logo 66% of the width
  * adaptive icon     108 x 108 dp canvas, logo 48 dp wide so it stays inside the
                       guaranteed 66 dp safe circle even on a round mask
  * monochrome layer  same geometry as the adaptive foreground, single colour
"""

from pathlib import Path
from typing import Optional, Tuple

from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parent.parent
LOGO_PATH = REPO_ROOT / "logos" / "logo.png"
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"
STORE_DIR = REPO_ROOT / "store-assets"

# Deep navy from res/values/colors.xml (ic_launcher_background). The logo's light
# blue would wash out on white, so the dark backdrop is what keeps it readable.
BACKDROP = (15, 23, 42, 255)

# dp multiplier per resource bucket.
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

LEGACY_ICON_DP = 48
ADAPTIVE_ICON_DP = 108
ADAPTIVE_LOGO_DP = 48
LEGACY_LOGO_RATIO = 0.66
ROUND_LOGO_RATIO = 0.58
LEGACY_CORNER_RATIO = 0.18

RESAMPLING = getattr(Image, "Resampling", Image).LANCZOS


def load_logo() -> Image.Image:
    """Return the logo cropped to its visible pixels, so padding never skews centring."""
    image = Image.open(LOGO_PATH).convert("RGBA")
    bbox = image.split()[3].getbbox()
    return image.crop(bbox) if bbox else image


def fit(logo: Image.Image, width: int) -> Image.Image:
    """Scale the logo to ``width`` px, preserving its aspect ratio."""
    height = max(1, round(width * logo.height / logo.width))
    return logo.resize((width, height), RESAMPLING)


def centered(canvas_size: int, logo: Image.Image, width: int) -> Image.Image:
    """Paint a transparent canvas with the logo scaled to ``width`` and centred."""
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    art = fit(logo, width)
    offset = ((canvas_size - art.width) // 2, (canvas_size - art.height) // 2)
    canvas.paste(art, offset, art)
    return canvas


def solid_background(size: int, mask: str) -> Image.Image:
    """Opaque backdrop: rounded square for the legacy icon, circle for the round one."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    if mask == "circle":
        draw.ellipse((0, 0, size - 1, size - 1), fill=BACKDROP)
    else:
        radius = round(size * LEGACY_CORNER_RATIO)
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=BACKDROP)
    return canvas


def compose_backdrop(logo: Image.Image, size: int, mask: str, width_ratio: float) -> Image.Image:
    layer = solid_background(size, mask)
    art = fit(logo, round(size * width_ratio))
    offset = ((size - art.width) // 2, (size - art.height) // 2)
    layer.paste(art, offset, art)
    return layer


def silhouette(logo: Image.Image, size: int, width_ratio: float) -> Image.Image:
    """Single-colour (white) copy of the logo, used as the themed-icon layer."""
    art = fit(logo, round(size * width_ratio))
    shape = Image.new("RGBA", art.size, (255, 255, 255, 0))
    shape.putalpha(art.split()[3])
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(shape, ((size - shape.width) // 2, (size - shape.height) // 2), shape)
    return canvas


def save(
    image: Image.Image,
    path: Path,
    background: Optional[Tuple[int, int, int, int]] = None,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if background is None:
        image.save(path, "PNG", optimize=True)
    else:
        flat = Image.new("RGBA", image.size, background)
        flat.alpha_composite(image)
        flat.convert("RGB").save(path, "PNG", optimize=True)
    print(f"  {path.relative_to(REPO_ROOT).as_posix()}  {image.width}x{image.height}")


def main() -> None:
    logo = load_logo()
    print(
        f"logo source: {LOGO_PATH.relative_to(REPO_ROOT).as_posix()} "
        f"(art {logo.width}x{logo.height})"
    )

    for bucket, scale in DENSITIES.items():
        mipmap = RES_DIR / f"mipmap-{bucket}"

        legacy_px = round(LEGACY_ICON_DP * scale)
        save(
            compose_backdrop(logo, legacy_px, "rounded", LEGACY_LOGO_RATIO),
            mipmap / "ic_launcher.png",
        )
        save(
            compose_backdrop(logo, legacy_px, "circle", ROUND_LOGO_RATIO),
            mipmap / "ic_launcher_round.png",
        )

        adaptive_px = round(ADAPTIVE_ICON_DP * scale)
        save(
            centered(adaptive_px, logo, round(ADAPTIVE_LOGO_DP * scale)),
            mipmap / "ic_launcher_foreground.png",
        )
        save(
            silhouette(logo, adaptive_px, ADAPTIVE_LOGO_DP / ADAPTIVE_ICON_DP),
            mipmap / "ic_launcher_monochrome.png",
        )

    # In-app logo: 640 px wide covers a 160 dp slot on an xxxhdpi screen without upscaling.
    save(fit(logo, 640), RES_DIR / "drawable-nodpi" / "logo.png")

    # Store listings want opaque 512x512 artwork.
    save(
        compose_backdrop(logo, 512, "rounded", LEGACY_LOGO_RATIO),
        STORE_DIR / "ic_launcher_512.png",
        background=BACKDROP,
    )
    save(
        compose_backdrop(logo, 512, "circle", ROUND_LOGO_RATIO),
        STORE_DIR / "ic_launcher_round_512.png",
        background=BACKDROP,
    )


if __name__ == "__main__":
    main()
