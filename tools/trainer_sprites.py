#!/usr/bin/env python3
"""Cut BW trainer sprites out of The Spriters Resource sheet and map them to HGSS frontier trainer classes.

Sheet: "Trainers" (sheet 34021) from https://www.spriters-resource.com/ds_dsi/pokemonblackwhite/
The site itself is behind a bot check, so the sheet is fetched through the Wayback Machine.
The sheet is an 8 x 13 grid of 86 x 128 cells, each on a solid background color, which is made transparent.

Requires Pillow.
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

# HGSS trainer class id -> (sheet cell, Korean class name)
CLASSES = {
    2: (2, "반바지 꼬마"), 3: (3, "짧은 치마"), 4: (27, "캠핑보이"), 5: (28, "피크닉걸"),
    6: (14, "곤충채집소년"), 7: (20, "아로마아가씨"), 9: (79, "등산가"), 10: (69, "배틀걸"),
    11: (29, "낚시꾼"), 12: (95, "사이클링"), 13: (96, "사이클링"), 14: (56, "태권왕"),
    16: (47, "포켓몬브리더"), 17: (13, "포켓몬브리더"), 18: (18, "카우걸"), 19: (58, "조깅맨"),
    20: (64, "애호가클럽"), 21: (65, "애호가클럽"), 22: (15, "포켓몬키즈"),
    24: (99, "엘리트트레이너"), 25: (100, "엘리트트레이너"), 53: (99, "엘리트트레이너"), 54: (100, "엘리트트레이너"),
    26: (9, "웨이트리스"), 81: (8, "웨이터"), 27: (81, "베테랑"), 28: (50, "닌자보이"),
    29: (61, "드래곤조련사"), 30: (49, "새조련사"), 32: (47, "도련님"), 33: (48, "아가씨"),
    34: (72, "신사"), 35: (76, "마담"), 36: (52, "미녀"), 37: (33, "콜렉터"), 38: (91, "경찰관"),
    39: (24, "포켓몬레인저"), 40: (25, "포켓몬레인저"), 41: (57, "연구원"), 44: (89, "튜브꼬마"),
    45: (90, "튜브꼬마"), 46: (88, "뱃사람"), 48: (26, "유적마니아"), 49: (35, "초능력자"),
    50: (36, "초능력자"), 51: (75, "탐정"), 52: (30, "기타리스트"), 57: (77, "스킨헤드"),
    58: (32, "피에로"), 59: (17, "작업원"), 60: (4, "학원생"), 61: (5, "학원생"), 71: (22, "목장주인"),
    80: (70, "파라솔아가씨"), 83: (71, "카메라맨"), 84: (53, "리포터"), 85: (21, "아이돌"),
    100: (98, "팩토리헤드"),
}
PLAYER_CELL = 0


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

    def cut(cell, name):
        x, y = (cell % COLS) * CELL_W, (cell // COLS) * CELL_H
        img = sheet.crop((x, y, x + CELL_W, y + CELL_H))
        bg = img.getpixel((0, 0))
        img.putdata([(0, 0, 0, 0) if p[:3] == bg[:3] else p for p in img.getdata()])
        img.save(os.path.join(out_dir, name))

    for cell in sorted({c for c, _ in CLASSES.values()} | {PLAYER_CELL}):
        cut(cell, "%d.png" % cell)

    tp = os.path.join(OUT, "data", "trainers.json")
    trainers = json.load(open(tp, encoding="utf-8"))
    for t in trainers:
        cell, ko = CLASSES.get(t["trainerClass"], (PLAYER_CELL, t["className"]))
        t["sprite"] = cell
        t["classKo"] = ko
    with open(tp, "w", encoding="utf-8") as f:
        json.dump(trainers, f, ensure_ascii=False, separators=(",", ":"))
    print("trainer sprites:", len(os.listdir(out_dir)))


if __name__ == "__main__":
    main()
