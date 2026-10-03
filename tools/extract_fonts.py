#!/usr/bin/env python3
"""Extract the base64 @font-face fonts embedded in the reference HTML into .woff2 files.

Usage: python3 tools/extract_fonts.py docs/reference/newspaper.html app/src/main/assets/newspaper/fonts
"""
import base64, re, sys, pathlib

NAMES = {
    ("UnifrakturMaguntia", "normal", "400"): "UnifrakturMaguntia-Regular.woff2",
    ("Libre Caslon Text", "normal", "400"): "LibreCaslonText-Regular.woff2",
    ("Libre Caslon Text", "italic", "400"): "LibreCaslonText-Italic.woff2",
    ("Libre Caslon Text", "normal", "700"): "LibreCaslonText-Bold.woff2",
    ("Playfair Display", "normal", "700"): "PlayfairDisplay-Bold.woff2",
    ("Playfair Display", "normal", "900"): "PlayfairDisplay-Black.woff2",
}

src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
pat = re.compile(r"@font-face\{font-family:'([^']+)';font-style:(\w+);font-weight:(\d+);"
                 r"src:url\(data:font/woff2;base64,([A-Za-z0-9+/=]+)\)")
found = 0
for fam, style, weight, b64 in pat.findall(src.read_text()):
    name = NAMES[(fam, style, weight)]
    (out / name).write_bytes(base64.b64decode(b64))
    print(name)
    found += 1
if found != len(NAMES):
    sys.exit(f"expected {len(NAMES)} fonts, found {found}")
