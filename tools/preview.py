#!/usr/bin/env python3
"""Desktop preview of the newspaper template, no Android needed.

Copies app/src/main/assets/newspaper into build/preview/, injects an edition JSON
(default: fixtures/sample_edition.json) and, if a Chromium-based browser is found,
prints build/preview/edition.pdf. Open build/preview/template.html in a browser to view.

The QR code is generated in Kotlin, so it is absent in this preview.

Usage: python3 tools/preview.py [edition.json] [--browser /path/to/chromium]
"""
import argparse, json, pathlib, shutil, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/newspaper"
OUT = ROOT / "build/preview"
BROWSERS = ["chrome-headless-shell", "chromium", "google-chrome", "google-chrome-stable", "brave", "brave-browser", "brave-origin"]

ap = argparse.ArgumentParser()
ap.add_argument("edition", nargs="?", default=str(ROOT / "fixtures/sample_edition.json"))
ap.add_argument("--browser")
args = ap.parse_args()

data = json.loads(pathlib.Path(args.edition).read_text())
shutil.rmtree(OUT, ignore_errors=True)
shutil.copytree(ASSETS, OUT)
(OUT / "preview-data.js").write_text(
    "window.renderEdition(" + json.dumps(data) + ").then(function(r){"
    "document.title=JSON.stringify(r);console.log('REPORT '+JSON.stringify(r));});\n")
html = (OUT / "template.html").read_text()
html = html.replace('<script src="render.js"></script>',
                    '<script src="render.js"></script>\n<script src="preview-data.js"></script>')
(OUT / "template.html").write_text(html)
print(f"wrote {OUT / 'template.html'}")

browser = args.browser or next((b for b in map(shutil.which, BROWSERS) if b), None)
if not browser:
    sys.exit("no Chromium-based browser found; open the HTML file manually")
base = [browser, "--headless", "--disable-gpu", "--no-sandbox", "--allow-file-access-from-files",
        "--virtual-time-budget=8000"]
url = (OUT / "template.html").as_uri()
subprocess.run(base + ["--no-pdf-header-footer", f"--print-to-pdf={OUT / 'edition.pdf'}", url],
               check=True, stderr=subprocess.DEVNULL)
dump = subprocess.run(base + ["--dump-dom", url], capture_output=True, text=True).stdout
start = dump.find("<title>") + 7
print("report:", dump[start:dump.find("</title>")].replace("&quot;", '"'))
print(f"wrote {OUT / 'edition.pdf'}")
