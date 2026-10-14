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

SUBWAY = os.path.join(BW, "data", "facilities", "battle_subway")
# The player's Pokémon pool comes from the PWT Rental Tournament. order.json has 1000 sets; the
# Master-rank rental draws from arc file range 209..908 (sRentalFiles[*][1][*] in src/ov055/wbt_party.c:
# group0 = 209..558, group1 = 559..908). Trainers/facility structure still come from the Battle Subway.
PWT_RENTAL = os.path.join(BW, "data", "facilities", "pwt_rental")
PWT_MASTER_FIRST = 209
PWT_MASTER_LAST = 908
KO = "3"

# Moves not on any set but needed by the engine.
EXTRA_MOVES = {"MOVE_STRUGGLE"}

# Battle Subway trainer class -> (Korean name, BW-trainer sprite cell). The sprite cells are the BW "Trainers"
# sheet cells cut by trainer_sprites.py; Subway classes are mapped to the closest-looking BW trainer. The two
# Subway Bosses (Ingo/Emmet) have no BW sprite, so they reuse the Veteran cell (81) as a stand-in.
CLASS_INFO = {
    "TRAINER_CLASS_YOUNGSTER": ("반바지 꼬마", 2),
    "TRAINER_CLASS_LASS": ("짧은 치마", 3),
    "TRAINER_CLASS_SCHOOL_KID_M": ("학원생", 4),
    "TRAINER_CLASS_SCHOOL_KID_F": ("학원생", 5),
    "TRAINER_CLASS_RICH_BOY": ("도련님", 47),
    "TRAINER_CLASS_LADY": ("아가씨", 48),
    "TRAINER_CLASS_PRESCHOOLER_M": ("유치원생", 15),
    "TRAINER_CLASS_PRESCHOOLER_F": ("유치원생", 15),
    "TRAINER_CLASS_BACKPACKER_M": ("백팩커", 79),
    "TRAINER_CLASS_BACKPACKER_F": ("백팩커", 70),
    "TRAINER_CLASS_ACE_TRAINER_M": ("엘리트 트레이너", 99),
    "TRAINER_CLASS_ACE_TRAINER_F": ("엘리트 트레이너", 100),
    "TRAINER_CLASS_VETERAN_M": ("베테랑", 81),
    "TRAINER_CLASS_VETERAN_F": ("베테랑", 76),
    "TRAINER_CLASS_BATTLE_GIRL": ("배틀걸", 69),
    "TRAINER_CLASS_BLACK_BELT": ("태권왕", 56),
    "TRAINER_CLASS_PSYCHIC_M": ("초능력자", 35),
    "TRAINER_CLASS_PSYCHIC_F": ("초능력자", 36),
    "TRAINER_CLASS_PKMN_RANGER_M": ("포켓몬레인저", 24),
    "TRAINER_CLASS_PKMN_RANGER_F": ("포켓몬레인저", 25),
    "TRAINER_CLASS_SCIENTIST_M_52": ("연구원", 57),
    "TRAINER_CLASS_SCIENTIST_F_46": ("연구원", 57),
    "TRAINER_CLASS_PKMN_BREEDER_M": ("포켓몬브리더", 47),
    "TRAINER_CLASS_PKMN_BREEDER_F": ("포켓몬브리더", 13),
    "TRAINER_CLASS_POKEFAN_M": ("애호가클럽", 64),
    "TRAINER_CLASS_POKEFAN_F": ("애호가클럽", 65),
    "TRAINER_CLASS_ARTIST": ("화가", 33),
    "TRAINER_CLASS_BAKER": ("제빵사", 9),
    "TRAINER_CLASS_BIKER": ("폭주족", 77),
    "TRAINER_CLASS_ROUGHNECK": ("스킨헤드", 77),
    "TRAINER_CLASS_CLERK_F": ("회사원", 9),
    "TRAINER_CLASS_CLERK_M_66": ("회사원", 8),
    "TRAINER_CLASS_CLERK_M_67": ("회사원", 8),
    "TRAINER_CLASS_CYCLIST_F": ("사이클링", 96),
    "TRAINER_CLASS_CYCLIST_M": ("사이클링", 95),
    "TRAINER_CLASS_DEPOT_AGENT": ("역무원", 8),
    "TRAINER_CLASS_DOCTOR": ("의사", 57),
    "TRAINER_CLASS_NURSE": ("간호사", 9),
    "TRAINER_CLASS_FISHERMAN": ("낚시꾼", 29),
    "TRAINER_CLASS_GENTLEMAN": ("신사", 72),
    "TRAINER_CLASS_HARLEQUIN_32": ("피에로", 32),
    "TRAINER_CLASS_HIKER": ("등산가", 79),
    "TRAINER_CLASS_JANITOR": ("관리인", 59),
    "TRAINER_CLASS_MAID": ("메이드", 76),
    "TRAINER_CLASS_NURSERY_AIDE": ("보육교사", 76),
    "TRAINER_CLASS_PARASOL_LADY": ("파라솔아가씨", 70),
    "TRAINER_CLASS_PILOT": ("파일럿", 88),
    "TRAINER_CLASS_POLICEMAN": ("경찰관", 91),
    "TRAINER_CLASS_SOCIALITE": ("상류층", 48),
    "TRAINER_CLASS_WAITER": ("웨이터", 8),
    "TRAINER_CLASS_WAITRESS": ("웨이트리스", 9),
    "TRAINER_CLASS_WORKER_26": ("작업원", 17),
    "TRAINER_CLASS_WORKER_44": ("작업원", 17),
    "TRAINER_CLASS_PKMN_TRAINER_NATE": ("포켓몬트레이너", 0),
    "TRAINER_CLASS_PKMN_TRAINER_ROSA": ("포켓몬트레이너", 1),
    "TRAINER_CLASS_SUBWAY_BOSS_88": ("배틀서브웨이 보스", 81),
    "TRAINER_CLASS_SUBWAY_BOSS_102": ("배틀서브웨이 보스", 81),
}
TRAINER_CLASS_NAMES = {}  # filled from trainer_classes/*.json
CLASS_ID = {}  # assigned in order of appearance
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
    bw_species = bw_consts("species.txt")  # SPECIES_* -> national dex no.
    bw_moves = bw_consts("moves.txt")
    bw_items = bw_consts("items.txt")
    bw_abil = bw_consts("abilities.txt")
    bw_nat = bw_consts("natures.txt")
    bw_types = bw_consts("types.txt")

    for key in CLASS_INFO:
        d = const_to_dir(key, "TRAINER_CLASS_").rstrip("0123456789_")
        p = os.path.join(BW, "data", "trainer_classes", const_to_dir(key, "TRAINER_CLASS_") + ".json")
        if os.path.exists(p):
            TRAINER_CLASS_NAMES[key] = json.load(open(p, encoding="utf-8"))["name"]

    # Trainer order/structure still comes from the Battle Subway.
    subway_order = json.load(open(os.path.join(SUBWAY, "order.json"), encoding="utf-8"))
    # Pokémon sets come from the PWT Rental Tournament arc instead.
    pwt_order = json.load(open(os.path.join(PWT_RENTAL, "order.json"), encoding="utf-8"))
    # The battle code addresses sets by arc file index, which is the order.json position.
    set_index = {name: i for i, name in enumerate(pwt_order["pokemon"])}

    # The PWT Rental Master pool: arc file indices 209..908 (see wbt_party.c sRentalFiles). These are the
    # Pokémon the player and opponents draw from. The flag keeps the field name "super" so the engine's
    # existing eligibility code (GameData.superSetIds / FactorySet.superCourse) works unchanged.
    master_pool = set(range(PWT_MASTER_FIRST, PWT_MASTER_LAST + 1))

    # ---- sets (PWT Rental: 1000 named sets, in order.json order)
    sets, used_species, used_moves, used_items = {}, set(), set(), set()
    for name, i in set_index.items():
        j = json.load(open(os.path.join(PWT_RENTAL, "pokemon", name + ".json"), encoding="utf-8"))
        if j["species"] == "SPECIES_NONE":
            # Keep an index placeholder so later indices still line up, but mark it empty.
            sets[i] = {"id": i, "species": 0, "moves": [], "evs": [False] * 6, "nature": 0, "item": 0, "form": 0}
            continue
        sp = bw_species[j["species"]]
        moves = [bw_moves[m] for m in j["moves"] if m != "MOVE_NONE"]
        ev = j["ev_flags"]
        item = bw_items[j["item"]]
        in_master = i in master_pool
        sets[i] = {
            "id": i, "species": sp, "moves": moves,
            # HP, Atk, Def, Spe, SpA, SpD (set evFlags bit order)
            "evs": [ev["hp"], ev["attack"], ev["defense"], ev["speed"], ev["special_attack"], ev["special_defense"]],
            "nature": bw_nat[j["nature"]], "item": item, "form": j["form"],
            "super": in_master,  # in the PWT Rental Master pool (reuses the "super" flag/field)
        }
        # Only the Master-pool sets actually appear in-game, so only their species/moves/items are needed.
        if in_master:
            used_species.add(j["species"])
            for m in j["moves"]:
                if m != "MOVE_NONE":
                    used_moves.add(m)
            if item:
                used_items.add(j["item"])

    # ---- species (every species a subway set uses)
    # A species is "final" (fully evolved) when it has no further evolutions.
    final_evo = {}
    for key in bw_species:
        d = const_to_dir(key, "SPECIES_")
        p = os.path.join(BW, "data", "pokemon", d, "data.json")
        if os.path.exists(p):
            final_evo[bw_species[key]] = not json.load(open(p, encoding="utf-8")).get("evolutions")
    ko_sp = ko_names("pokemon_species_names", "pokemon_species_id")
    species_out, used_abilities = [], set()
    sprite_dirs = {"front": "front_single.png", "back": "back_single.png", "icon": "icon.png"}
    for key in sorted(used_species, key=lambda s: bw_species[s]):
        sp = bw_species[key]
        d = const_to_dir(key, "SPECIES_")
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
            "final": final_evo.get(sp, True),
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
    for c in sorted({m for m in used_moves} | EXTRA_MOVES, key=lambda c: bw_moves[c]):
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
            # hold_effect is usually HOLD_EFFECT_*, but gems/Rocky Helmet/Air Balloon etc. use the numeric id 147.
            "holdEffect": str(strip_prefix(j["hold_effect"], "HOLD_EFFECT_")), "holdParam": j["hold_param"],
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

    # ---- trainers (Battle Subway, in order.json order; used only for class name/sprite/opponent names)
    # In a PWT Rental Tournament everyone draws from the same shared rental pool, so trainers carry no
    # per-trainer set list: opponents are built from the Master pool (GameData.superSetIds) in FactoryRules.
    trainers_out = []
    for i, name in enumerate(subway_order["trainers"]):
        j = json.load(open(os.path.join(SUBWAY, "trainers", name + ".json"), encoding="utf-8"))
        cls = j["class"]
        ko, sprite = CLASS_INFO.get(cls, ("트레이너", 0))
        trainers_out.append({
            "id": i, "trainerClass": CLASS_ID.setdefault(cls, len(CLASS_ID)),
            "classKey": cls, "className": TRAINER_CLASS_NAMES.get(cls, cls), "classKo": ko,
            "name": j.get("name", "?"), "sprite": sprite, "sets": [],
        })

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
