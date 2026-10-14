#!/usr/bin/env python3
"""Cut BW2 battle backgrounds out of a B2W2 battle-background rip sheet.

The Spriters Resource has no BW2 battle backgrounds (in-game they are 3D), so this uses
"Pokemon B2W2 Battle Backgrounds" by Snivy101 on DeviantArt:
https://www.deviantart.com/snivy101/art/Pokemon-B2W2-Battle-Backgrounds-319706977
Place the full-size sheet at tools/cache/b2w2_battle_backgrounds.png (2016 x 1588).

The sheet is a grid of 252-px-wide cells; each crop is 250 x 198 (the battle camera view).
Requires Pillow.
"""
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHEET = os.path.join(ROOT, "tools", "cache", "b2w2_battle_backgrounds.png")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sprites", "battle_bg")

# name -> (column, top, bottom). These are the single-battle stages (one far + one near platform),
# NOT the twin-ring rotation-battle stage. Rows begin at these y offsets on the sheet.
CELLS = {
    "facility_0": (1, 0, 198),      # grass, day
    "facility_1": (1, 199, 397),    # water, day
    "facility_2": (1, 398, 596),    # grass, overcast
    "facility_3": (0, 796, 994),    # sand, dusk
    "facility_4": (2, 597, 795),    # sand, sunset
}


def main():
    sheet = Image.open(SHEET).convert("RGB")
    os.makedirs(OUT, exist_ok=True)
    for name, (col, top, bottom) in CELLS.items():
        sheet.crop((col * 252 + 1, top, col * 252 + 251, bottom)).save(os.path.join(OUT, name + ".png"))
    print("battle backgrounds:", len(CELLS))


if __name__ == "__main__":
    main()
