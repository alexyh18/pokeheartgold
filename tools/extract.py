#!/usr/bin/env python3
"""Build the game's asset data from the two decomp repos.

Inputs
  ../pokeheartgold : Battle Factory sets (files/a/2/0/3) and trainers (files/a/2/0/2),
                     trainer names (msg_0027) and trainer class names (msg_0730).
  ../pokebw2       : Gen 5 species/move/item data (JSON) and Pokémon sprites (PNG).
  tools/cache/*.csv: PokeAPI CSVs, used only for Korean names and descriptions.

Output (app/src/main/assets)
  data/species.json, moves.json, items.json, abilities.json, natures.json,
  data/sets.json, trainers.json, sprites/front/<id>.png, sprites/back/<id>.png, sprites/icon/<id>.png

Only Open Level data is exported: Factory set ids 351..950 (the ranges used by
the Open Level tables ov80_0223C4B8 / ov80_0223C558), and only Gen 4 species (<= 493).
"""
import csv
import json
import os
import re
import shutil
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WS = os.path.dirname(ROOT)
HG = os.path.join(WS, "pokeheartgold")
BW = os.path.join(WS, "pokebw2")
CACHE = os.path.join(ROOT, "tools", "cache")
OUT = os.path.join(ROOT, "app", "src", "main", "assets")

SET_MIN, SET_MAX = 351, 950
MAX_SPECIES = 493
KO = "3"
# BW2 stat-change targets -> engine stage names (8 = every stat, used by Ancient Power and friends)
STAT_KEYS = {
    "BATTLEMON_ATTACK_STAGE": "ATK", "BATTLEMON_DEFENSE_STAGE": "DEF", "BATTLEMON_SPEED_STAGE": "SPE",
    "BATTLEMON_SP_ATTACK_STAGE": "SPA", "BATTLEMON_SP_DEFENSE_STAGE": "SPD",
    "BATTLEMON_ACCURACY_STAGE": "ACC", "BATTLEMON_EVASION_STAGE": "EVA", 8: "ALL",
}


def narc(path):
    b = open(path, "rb").read()
    assert b[:4] == b"NARC"
    o = struct.unpack_from("<H", b, 0xC)[0]
    assert b[o:o + 4] == b"BTAF"
    size, count = struct.unpack_from("<IH", b, o + 4)
    fat = [struct.unpack_from("<II", b, o + 12 + 8 * i) for i in range(count)]
    o += size
    assert b[o:o + 4] == b"BTNF"
    o += struct.unpack_from("<I", b, o + 4)[0]
    assert b[o:o + 4] == b"GMIF"
    d = o + 8
    return [b[d + s:d + e] for s, e in fat]


def hg_consts(name, prefix):
    out = {}
    for line in open(os.path.join(HG, "include", "constants", name)):
        m = re.match(r"#define\s+(" + prefix + r"\w+)\s+(\d+)\s*$", line)
        if m and int(m.group(2)) not in out:
            out[int(m.group(2))] = m.group(1)
    return out


def bw_consts(name):
    """BW2 constant lists: 'NAME = n' sets the counter, otherwise +1."""
    out, n = {}, -1
    for line in open(os.path.join(BW, "data", "constants", name)):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = [x.strip() for x in line.split("=")]
            n = int(v, 0)
        else:
            k = line
            n += 1
        out[k] = n
    return out


def msg(bank):
    d = json.load(open(os.path.join(HG, "files", "msgdata", "msg", bank + ".json"), encoding="utf-8"))
    return [m.get("en_US", "") for m in d["messages"]]


def csv_rows(name):
    return list(csv.DictReader(open(os.path.join(CACHE, name + ".csv"), encoding="utf-8")))


def ko_names(table, idcol):
    return {r[idcol]: r["name"] for r in csv_rows(table) if r["local_language_id"] == KO}


def ko_flavor(table, idcol):
    best = {}
    for r in csv_rows(table):
        if r["language_id"] != KO:
            continue
        vg = int(r["version_group_id"])
        k = r[idcol]
        if k not in best or vg < best[k][0]:
            best[k] = (vg, r["flavor_text"].replace("\n", " "))
    return {k: v[1] for k, v in best.items()}


def ident_map(table):
    return {r["identifier"]: r["id"] for r in csv_rows(table)}


def const_to_dir(c, prefix):
    return c[len(prefix):].lower()


def strip_prefix(v, prefix):
    return v[len(prefix):] if isinstance(v, str) and v.startswith(prefix) else v


def write(rel, obj):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))


def main():
    hg_species = hg_consts("species.h", "SPECIES_")
    hg_moves = hg_consts("moves.h", "MOVE_")
    hg_items = hg_consts("items.h", "ITEM_")
    bw_moves = bw_consts("moves.txt")
    bw_items = bw_consts("items.txt")
    bw_items_by_id = {v: k for k, v in bw_items.items()}
    bw_abil = bw_consts("abilities.txt")
    bw_nat = bw_consts("natures.txt")
    bw_types = bw_consts("types.txt")

    raw_sets = narc(os.path.join(HG, "files", "a", "2", "0", "3"))
    raw_trainers = narc(os.path.join(HG, "files", "a", "2", "0", "2"))

    # ---- sets
    sets, used_species, used_moves, used_items = {}, set(), set(), set()
    for i in range(SET_MIN, SET_MAX + 1):
        sp, m1, m2, m3, m4, evs, nature, item, form = struct.unpack("<H4HBBHH", raw_sets[i])
        if sp > MAX_SPECIES:
            continue
        moves = []
        for m in (m1, m2, m3, m4):
            if m:
                c = hg_moves[m]
                assert c in bw_moves, c
                moves.append(bw_moves[c])
                used_moves.add(c)
        # Gen 4 hold-item ids are kept unchanged in Gen 5 (only spellings differ), so map by id.
        icon = bw_items_by_id[item] if item else "ITEM_NONE"
        if item:
            used_items.add(icon)
        sets[i] = {
            "id": i, "species": sp, "moves": moves,
            "evs": [bool(evs & (1 << s)) for s in range(6)],  # HP, Atk, Def, Spe, SpA, SpD
            "nature": nature, "item": bw_items[icon] if item else 0, "form": form,
        }
        used_species.add(sp)

    # ---- species (export every Gen 4 species that appears)
    ko_sp = ko_names("pokemon_species_names", "pokemon_species_id")
    species_out, used_abilities = [], set()
    sprite_dirs = {"front": "front_single.png", "back": "back_single.png", "icon": "icon.png"}
    for sp in sorted(used_species):
        d = const_to_dir(hg_species[sp], "SPECIES_")
        p = os.path.join(BW, "data", "pokemon", d, "data.json")
        j = json.load(open(p, encoding="utf-8"))
        bs = j["base_stats"]
        ab = [a for a in j["abilities"] if a != "ABILITY_NONE"]
        for a in ab:
            used_abilities.add(a)
        species_out.append({
            "id": sp, "name": j["name"], "nameKo": ko_sp.get(str(sp), j["name"]),
            "types": [bw_types[t] for t in dict.fromkeys(j["types"])],
            "base": [bs["hp"], bs["attack"], bs["defense"], bs["speed"], bs["special_attack"], bs["special_defense"]],
            "abilities": [bw_abil[a] for a in ab],
            "weight": j["weight"],
            "genderRatio": {"GENDER_RATIO_MALE_ONLY": 0, "GENDER_RATIO_FEMALE_ONLY": 254,
                            "GENDER_RATIO_GENDERLESS": 255}.get(j["gender_ratio"], j["gender_ratio"]),
        })
        for kind, fname in sprite_dirs.items():
            src = os.path.join(BW, "data", "pokemon", d, fname)
            dst = os.path.join(OUT, "sprites", kind, "%d.png" % sp)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)

    # ---- moves
    move_ids = ident_map("moves")
    ko_mv = ko_names("move_names", "move_id")
    ko_mv_desc = ko_flavor("move_flavor_text", "move_id")
    moves_out = []
    for c in sorted(used_moves, key=lambda c: bw_moves[c]):
        d = const_to_dir(c, "MOVE_")
        j = json.load(open(os.path.join(BW, "data", "moves", d, "data.json"), encoding="utf-8"))
        pid = move_ids.get(d.replace("_", "-"), str(bw_moves[c]))
        moves_out.append({
            "id": bw_moves[c], "key": d, "name": j["name"], "nameKo": ko_mv.get(pid, j["name"]),
            "desc": ko_mv_desc.get(pid, j["description"].replace("\n", " ")),
            "type": bw_types[j["type"]],
            "category": strip_prefix(j["category"], "MOVE_CATEGORY_"),
            "power": j["power"], "accuracy": j["accuracy"], "pp": j["pp"], "priority": j["priority"],
            "hitsMin": j["hits"]["min"], "hitsMax": j["hits"]["max"],
            # Tri Attack stores 0xFFFF ("one of burn/freeze/paralysis") as its condition.
            "inflict": "TRI" if j["inflicts"]["condition"] == 65535 else strip_prefix(j["inflicts"]["condition"], "CONDITION_"),
            "inflictChance": j["inflicts"]["chance"],
            "crit": j["crit_stage"], "flinch": j["flinch_chance"],
            "effect": strip_prefix(j["effect"], "BATTLE_EFFECT_"),
            "drain": j["drain"], "heal": j["heal"],
            "target": strip_prefix(j["target"], "MOVE_TARGET_"),
            "stats": [{"stat": STAT_KEYS[s["stat"]], "stages": s["stages"], "chance": s["chance"]}
                      for s in j["stat_changes"]],
            "flags": [strip_prefix(f, "MOVE_FLAG_") for f in j["flags"]],
        })

    # ---- items
    item_ids = ident_map("items")
    ko_it = ko_names("item_names", "item_id")
    ko_it_desc = ko_flavor("item_flavor_text", "item_id")
    items_out = []
    for c in sorted(used_items, key=lambda c: bw_items[c]):
        d = const_to_dir(c, "ITEM_")
        j = json.load(open(os.path.join(BW, "data", "items", d, "data.json"), encoding="utf-8"))
        pid = item_ids.get(d.replace("_", "-"), item_ids.get(d.replace("_", "")))
        items_out.append({
            "id": bw_items[c], "key": d, "name": j["name"],
            "nameKo": ko_it.get(pid, j["name"]) if pid else j["name"],
            "desc": (ko_it_desc.get(pid) if pid else None) or j["description"].replace("\n", " "),
            "holdEffect": strip_prefix(j["hold_effect"], "HOLD_EFFECT_"), "holdParam": j["hold_param"],
            "flingPower": j["fling_power"],
        })

    # ---- abilities
    ko_ab = ko_names("ability_names", "ability_id")
    ko_ab_desc = ko_flavor("ability_flavor_text", "ability_id")
    abil_out = []
    for c in sorted(used_abilities, key=lambda c: bw_abil[c]):
        d = const_to_dir(c, "ABILITY_")
        j = json.load(open(os.path.join(BW, "data", "abilities", d + ".json"), encoding="utf-8"))
        aid = str(bw_abil[c])
        abil_out.append({"id": bw_abil[c], "key": d, "name": j["name"], "nameKo": ko_ab.get(aid, j["name"]),
                         "desc": ko_ab_desc.get(aid, j["description"].replace("\n", " "))})

    # ---- natures (game order 0..24)
    nat_ids = ident_map("natures")
    ko_nat = ko_names("nature_names", "nature_id")
    nat_out = []
    for c, n in sorted(bw_nat.items(), key=lambda x: x[1]):
        if n > 24:
            continue
        d = const_to_dir(c, "NATURE_")
        nat_out.append({"id": n, "key": d, "nameKo": ko_nat.get(nat_ids.get(d), d)})

    # ---- types
    ko_ty = ko_names("type_names", "type_id")
    types_out = []
    for c, n in sorted(bw_types.items(), key=lambda x: x[1]):
        if n > 16:
            continue
        types_out.append({"id": n, "key": const_to_dir(c, "TYPE_"), "nameKo": ko_ty.get(str(n + 1), c)})

    # ---- trainers
    names = msg("msg_0027")
    classes = msg("msg_0730")
    trainers_out = []
    for i, t in enumerate(raw_trainers):
        v = struct.unpack("<%dH" % (len(t) // 2), t)
        trainers_out.append({"id": i, "trainerClass": v[0], "className": classes[v[0]], "name": names[i]})

    write("data/sets.json", list(sets.values()))
    write("data/species.json", species_out)
    write("data/moves.json", moves_out)
    write("data/items.json", items_out)
    write("data/abilities.json", abil_out)
    write("data/natures.json", nat_out)
    write("data/types.json", types_out)
    write("data/trainers.json", trainers_out)
    print("sets=%d species=%d moves=%d items=%d abilities=%d trainers=%d" % (
        len(sets), len(species_out), len(moves_out), len(items_out), len(abil_out), len(trainers_out)))


if __name__ == "__main__":
    sys.exit(main())
