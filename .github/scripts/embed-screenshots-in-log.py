#!/usr/bin/env python3
"""Prints reduced copies of the device screenshots into the job log so they can be reviewed without artifact access.

The original PNGs stay in the uploaded artifact and are identified here by SHA-256. What is printed is a reduced JPEG
(720 px wide, quality 72) as base64 lines: it is a review aid, not the evidence file. No secrets are involved: the
screenshots are produced from public fixtures.

Format, one record per picture:
  EP07IMG-BEGIN <api> <name> sha256=<original png> original=<w>x<h> reduced=<w>x<h> jpegBytes=<n> chunks=<k>
  EP07IMG <api> <name> <index> <base64>
  EP07IMG-END <api> <name> jpegSha256=<digest of the reduced jpeg>
"""
import base64
import hashlib
import io
import shutil
import subprocess
import sys
from pathlib import Path

CHUNK = 3000


def pillow_image():
    try:
        from PIL import Image
    except ImportError:
        try:
            subprocess.run(
                [sys.executable, "-m", "pip", "install", "--quiet", "--break-system-packages", "pillow"],
                check=False, timeout=120, stdout=sys.stderr, stderr=sys.stderr,
            )
            from PIL import Image
        except (ImportError, OSError, subprocess.TimeoutExpired):
            return None
    return Image


def reduce(png: Path, image_module):
    if image_module is None:
        convert = shutil.which("convert")
        if convert is None:
            raise RuntimeError("Pillow and ImageMagick convert are both unavailable")
        png_bytes = png.read_bytes()
        original_text = subprocess.run(
            [convert, "png:-", "-format", "%w %h", "info:"], input=png_bytes,
            check=True, capture_output=True, timeout=30,
        ).stdout.decode("ascii")
        original = tuple(map(int, original_text.split()))
        jpeg = subprocess.run(
            [convert, "png:-", "-background", "white", "-alpha", "remove", "-alpha", "off",
             "-resize", "720x>", "-strip", "-quality", "72", "jpeg:-"],
            input=png_bytes, check=True, capture_output=True, timeout=30,
        ).stdout
        reduced_text = subprocess.run(
            [convert, "jpeg:-", "-format", "%w %h", "info:"], input=jpeg,
            check=True, capture_output=True, timeout=30,
        ).stdout.decode("ascii")
        return original, tuple(map(int, reduced_text.split())), jpeg
    Image = image_module
    with Image.open(png) as image:
        original = image.size
        rgb = image.convert("RGB")
        width = 720
        height = round(rgb.height * width / rgb.width)
        reduced = rgb.resize((width, height), Image.LANCZOS) if rgb.width > width else rgb
        buffer = io.BytesIO()
        reduced.save(buffer, "JPEG", quality=72, optimize=True)
        return original, reduced.size, buffer.getvalue()


def main() -> None:
    if len(sys.argv) != 3:
        print("EP07IMG-WARNING expected API and screenshot directory", file=sys.stderr)
        return
    api = sys.argv[1]
    directory = Path(sys.argv[2])
    pngs = sorted(directory.glob("*.png"))
    if not pngs:
        print(f"EP07IMG-WARNING no PNGs in {directory}", file=sys.stderr)
        return
    image_module = pillow_image()
    for png in pngs:
        try:
            original_hash = hashlib.sha256(png.read_bytes()).hexdigest()
            original, reduced_size, jpeg = reduce(png, image_module)
        except Exception as error:
            print(f"EP07IMG-WARNING {api} {png.name}: {type(error).__name__}: {error}", file=sys.stderr)
            continue
        encoded = base64.b64encode(jpeg).decode("ascii")
        chunks = [encoded[i:i + CHUNK] for i in range(0, len(encoded), CHUNK)]
        name = png.stem
        print(f"EP07IMG-BEGIN {api} {name} sha256={original_hash} original={original[0]}x{original[1]} "
              f"reduced={reduced_size[0]}x{reduced_size[1]} jpegBytes={len(jpeg)} chunks={len(chunks)}")
        for index, chunk in enumerate(chunks):
            print(f"EP07IMG {api} {name} {index} {chunk}")
        print(f"EP07IMG-END {api} {name} jpegSha256={hashlib.sha256(jpeg).hexdigest()}")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
