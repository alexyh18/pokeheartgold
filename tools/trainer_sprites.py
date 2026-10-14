#!/usr/bin/env python3
"""Cut BW trainer sprites out of The Spriters Resource sheet, for the cells used by extract.py's CLASS_INFO.

Sheet: "Trainers" (sheet 34021) from https://www.spriters-resource.com/ds_dsi/pokemonblackwhite/
The site is behind a bot check, so the sheet is fetched through the Wayback Machine.
It is an 8 x 13 grid of 86 x 128 cells, each on a solid background color that is made transparent.

Must run after extract.py (reads the trainer sprite cells it chose). Requires Pillow.
"""
import json
import os
import urllib.request

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", "cache")
OUT = os.path.join(ROOT, "app", "src", "main", "assets")
SHEET_URL = "https://web.archive.org/web/2024im_/https://www.spriters-resource.com/resources/sheets/31/34021.png"
CELL_W, CELL_H, COLS = 86, 128, 8


def main():
    os.makedirs(CACHE, exist_ok=True)
    sheet_path = os.path.join(CACHE, "bw_trainers_34021.png")
    if not os.path.exists(sheet_path):
        req = urllib.request.Request(SHEET_URL, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req) as r, open(sheet_path, "wb") as f:
            f.write(r.read())
    sheet = Image.open(sheet_path).convert("RGBA")
    out_dir = os.path.join(OUT, "sprites", "trainer")
    os.makedirs(out_dir, exist_ok=True)

    trainers = json.load(open(os.path.join(OUT, "data", "trainers.json"), encoding="utf-8"))
    cells = {t["sprite"] for t in trainers} | {0}
    for cell in sorted(cells):
        x, y = (cell % COLS) * CELL_W, (cell // COLS) * CELL_H
        img = sheet.crop((x, y, x + CELL_W, y + CELL_H))
        bg = img.getpixel((0, 0))
        img.putdata([(0, 0, 0, 0) if p[:3] == bg[:3] else p for p in img.get_flattened_data()] if hasattr(img, "get_flattened_data") else [(0, 0, 0, 0) if p[:3] == bg[:3] else p for p in img.getdata()])
        img.save(os.path.join(out_dir, "%d.png" % cell))
    print("trainer sprites:", len(cells))


if __name__ == "__main__":
    main()
