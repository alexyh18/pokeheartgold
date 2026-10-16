#!/usr/bin/env python3
"""Build the USUM Battle Tree game data for the Android app.

Inputs
  ../Tree-Sets/Tree Sets/   : the Battle Tree catalogue data (cristiannomartins/Tree-Sets)
      - "Pokemon-Table 1.csv"            : the Battle Tree sets (species/nature/item/4 moves/EV placement)
      - "Battle Tree Lookup - Pokedex.tsv": species base stats / types / abilities / gender / pre-evo
      - "Trainer-Table 1.csv"            : Battle Tree trainers (name/category/sex/streak start-end)
      - "Trainer Category-Table 1.csv"   : trainer categories
  PokeAPI (cached under tools/apicache/) : per-move stats + Korean names + sprites, fetched once.
  tools/cache/*.csv (PokeAPI CSV dump)   : Korean names for species/types/abilities/items/natures.

Output (app/src/main/assets)
  data/{species,moves,items,abilities,natures,types,sets,trainers}.json
  sprites/front/<dex>.png, sprites/back/<dex>.png, sprites/icon/<dex>.png

The JSON shapes match com.usumfactory.data.* (same contract the Gen5 app used), so the engine/UI
are reused unchanged. Gen 7 differences handled here: the Fairy type (id 17) and Gen 7 species/moves.
"""
import csv
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WS = os.path.dirname(ROOT)
TREE = os.path.join(WS, "Tree-Sets", "Tree Sets")
CACHE = os.path.join(ROOT, "tools", "cache")        # PokeAPI CSV dump (Korean names, move stats)
APICACHE = os.path.join(ROOT, "tools", "apicache")  # per-move / per-species JSON + sprites
OUT = os.path.join(ROOT, "app", "src", "main", "assets")
KO = "3"  # local_language_id for Korean in the PokeAPI CSVs

# Engine type ids (com.usumfactory.engine.Types), Gen 6+ incl. Fairy.
TYPE_ID = {
    "Normal": 0, "Fighting": 1, "Flying": 2, "Poison": 3, "Ground": 4, "Rock": 5, "Bug": 6,
    "Ghost": 7, "Steel": 8, "Fire": 9, "Water": 10, "Grass": 11, "Electric": 12, "Psychic": 13,
    "Ice": 14, "Dragon": 15, "Dark": 16, "Fairy": 17,
}
# PokeAPI type_id (1-based, its own order) -> engine type id, for Korean type names.
POKEAPI_TYPE = {
    1: 0, 2: 1, 3: 2, 4: 3, 5: 4, 6: 5, 7: 6, 8: 7, 9: 8, 10: 9, 11: 10, 12: 11, 13: 12,
    14: 13, 15: 14, 16: 15, 17: 16, 18: 17,
}
# Engine stat index by name (BattleMon.stats / nature order): HP,Atk,Def,Spe,SpA,SpD.
ENG_STAT = {"Atk": 1, "Def": 2, "Spe": 3, "SpA": 4, "SpD": 5}
# PokeAPI stat_id -> engine stat index.
POKEAPI_STAT = {2: 1, 3: 2, 6: 3, 4: 4, 5: 5}  # Atk,Def,Spe,SpA,SpD (HP=1 never +/-)

HTTP_HEADERS = {"User-Agent": "usum-factory-extract/1.0"}


def csv_rows(path, delim=","):
    with open(path, encoding="utf-8-sig") as f:
        return list(csv.reader(f, delimiter=delim))


def dict_rows(path, delim=","):
    with open(path, encoding="utf-8-sig") as f:
        return list(csv.DictReader(f, delimiter=delim))


def ko_names(table, idcol):
    out = {}
    for r in dict_rows(os.path.join(CACHE, table + ".csv")):
        if r.get("local_language_id") == KO:
            out[r[idcol]] = r["name"]
    return out


def ko_flavor(table, idcol):
    """Korean flavor text keyed by id, picking the latest game version and flattening newlines.

    The *_flavor_text.csv dumps use `language_id` (not `local_language_id`) and carry one row per
    version group; we keep the highest version_group_id (newest wording) for each entry.
    """
    best = {}  # id -> (version_group_id, text)
    path = os.path.join(CACHE, table + ".csv")
    if not os.path.exists(path):
        return {}
    for r in dict_rows(path):
        if r.get("language_id") != KO:
            continue
        rid = r.get(idcol)
        try:
            vg = int(r.get("version_group_id") or 0)
        except ValueError:
            vg = 0
        text = (r.get("flavor_text") or "").replace("\n", " ").replace("\f", " ").replace("\r", " ")
        text = " ".join(text.split()).strip()
        if not text:
            continue
        if rid not in best or vg >= best[rid][0]:
            best[rid] = (vg, text)
    return {k: v[1] for k, v in best.items()}


def norm(name):
    """Normalize a display name to a PokeAPI slug."""
    s = name.strip().lower()
    s = s.replace("\u2019", "'").replace("\u2018", "'")
    s = s.replace(". ", "-").replace(".", "").replace(" ", "-").replace("'", "")
    s = s.replace(":", "").replace("%", "").replace("é", "e")
    s = ABILITY_ALIAS.get(s, s)
    return s


# Z-crystals aren't in the PokeAPI CSV dump, so they use synthetic ids (9001+) consistent with the
# engine's It.Z_CRYSTALS. "<Set item name>" -> (synthetic id, engine type id, Korean name).
Z_CRYSTALS = {
    "Normalium Z": (9000, 0, "노말Z"), "Fightinium Z": (9001, 1, "격투Z"), "Flyinium Z": (9002, 2, "비행Z"),
    "Poisonium Z": (9003, 3, "독Z"), "Groundium Z": (9004, 4, "땅Z"), "Rockium Z": (9005, 5, "바위Z"),
    "Buginium Z": (9006, 6, "벌레Z"), "Ghostium Z": (9007, 7, "고스트Z"), "Steelium Z": (9008, 8, "강철Z"),
    "Firium Z": (9009, 9, "불꽃Z"), "Waterium Z": (9010, 10, "물Z"), "Grassium Z": (9011, 11, "풀Z"),
    "Electrium Z": (9012, 12, "전기Z"), "Psychium Z": (9013, 13, "에스퍼Z"), "Icium Z": (9014, 14, "얼음Z"),
    "Dragonium Z": (9015, 15, "드래곤Z"), "Darkinium Z": (9016, 16, "악Z"), "Fairium Z": (9017, 17, "페어리Z"),
}

# Data-file typos / Gen7-form names that must map to a base species or canonical slug.
ABILITY_ALIAS = {"telepahty": "telepathy"}
# Alternate-form set names -> PokeAPI form slug. Each becomes its OWN species entry (real types/stats/
# abilities/sprite from PokeAPI), so forms play correctly instead of as the base species. The Midday
# Lycanroc is the base species (745); the others are distinct PokeAPI forms with ids in the 10xxx range.
FORM_SETS = {
    "Lycanroc (Midday Form)": "lycanroc-midday",  # dex 745
    "Lycanroc (Midnight Form)": "lycanroc-midnight",
    "Oricorio (Baile Style)": "oricorio-baile",  # dex 741
    "Oricorio (Pa'u Style)": "oricorio-pau",
    "Oricorio (Pom-Pom Style)": "oricorio-pom-pom",
    "Oricorio (Sensu Style)": "oricorio-sensu",
    "Rotom (Heat Rotom)": "rotom-heat", "Rotom (Wash Rotom)": "rotom-wash",
    "Rotom (Frost Rotom)": "rotom-frost", "Rotom (Fan Rotom)": "rotom-fan", "Rotom (Mow Rotom)": "rotom-mow",
    "Ninetales (Alola Form)": "ninetales-alola", "Raichu (Alola Form)": "raichu-alola",
    "Sandslash (Alola Form)": "sandslash-alola", "Golem (Alola Form)": "golem-alola",
    "Muk (Alola Form)": "muk-alola", "Dugtrio (Alola Form)": "dugtrio-alola",
    "Exeggutor (Alola Form)": "exeggutor-alola", "Marowak (Alola Form)": "marowak-alola",
}
# Korean names for the forms (the PokeAPI species name is the base; forms share it, so label them).
FORM_NAME_KO = {
    "lycanroc-midnight": "루가루암(한밤)", "oricorio-pau": "춤추새(파우파우)", "oricorio-pompom": "춤추새(파파라차)",
    "oricorio-sensu": "춤추새(마이마이)", "rotom-heat": "로토무(히트)", "rotom-wash": "로토무(워시)",
    "rotom-frost": "로토무(프로스트)", "rotom-fan": "로토무(스핀)", "rotom-mow": "로토무(커트)",
    "ninetales-alola": "나인테일(알로라)", "raichu-alola": "라이츄(알로라)", "sandslash-alola": "고지(알로라)",
    "golem-alola": "딱구리(알로라)", "muk-alola": "질퍽이(알로라)", "dugtrio-alola": "닥트리오(알로라)",
    "exeggutor-alola": "나시(알로라)", "marowak-alola": "텅구리(알로라)",
}


# ---- PokeAPI fetch with on-disk cache ------------------------------------------------
def api_get(path):
    """GET https://pokeapi.co/api/v2/<path>, cached to tools/apicache/<path>.json."""
    safe = path.strip("/").replace("/", "_")
    cp = os.path.join(APICACHE, safe + ".json")
    if os.path.exists(cp):
        with open(cp, encoding="utf-8") as f:
            return json.load(f)
    url = "https://pokeapi.co/api/v2/%s/" % path.strip("/")
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers=HTTP_HEADERS)
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.load(r)
            os.makedirs(os.path.dirname(cp), exist_ok=True)
            with open(cp, "w", encoding="utf-8") as f:
                json.dump(data, f)
            return data
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
            if isinstance(e, urllib.error.HTTPError) and e.code == 404:
                return None
            time.sleep(1 + attempt)
    return None


def download(url, dest):
    if os.path.exists(dest) or not url:
        return os.path.exists(dest)
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers=HTTP_HEADERS)
            with urllib.request.urlopen(req, timeout=30) as r:
                blob = r.read()
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(blob)
            return True
        except Exception:
            time.sleep(1 + attempt)
    return False


def write(rel, obj):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))


# ---- natures -------------------------------------------------------------------------
def build_natures():
    """Engine nature id = (plus-1)*5 + (minus-1), plus/minus in engine stat order (Atk..SpD=1..5)."""
    ko = ko_names("nature_names", "nature_id")
    name_to_engine = {}
    out = [None] * 25
    for r in dict_rows(os.path.join(CACHE, "natures.csv")):
        inc = int(r["increased_stat_id"]); dec = int(r["decreased_stat_id"])
        if inc == dec:  # neutral nature -> plus==minus (any 1..5; use ATK slot => id 0,6,12,18,24)
            p = m = 1
        else:
            p = POKEAPI_STAT[inc]; m = POKEAPI_STAT[dec]
        eid = (p - 1) * 5 + (m - 1)
        key = r["identifier"]
        # Neutral natures all collapse to a +Atk/-Atk style id; spread them to the 5 diagonal ids.
        name_to_engine[key.capitalize()] = eid
        out[eid] = {"id": eid, "key": key, "nameKo": ko.get(r["id"], key)}
    # Fill any missing diagonal (neutral) ids defensively.
    for i in range(25):
        if out[i] is None:
            out[i] = {"id": i, "key": "nature%d" % i, "nameKo": "-"}
    return out, name_to_engine


# ---- types ---------------------------------------------------------------------------
def build_types():
    ko = ko_names("type_names", "type_id")
    keys = {v: k.lower() for k, v in TYPE_ID.items()}
    out = []
    for eid in range(18):
        # find the PokeAPI type_id whose engine id == eid
        pid = next((p for p, e in POKEAPI_TYPE.items() if e == eid), None)
        out.append({"id": eid, "key": keys[eid], "nameKo": ko.get(str(pid), keys[eid])})
    return out


def build_zmove_names():
    """Korean display names for the 18 type-based damaging Z-moves, keyed by engine type id.

    The PokeAPI dump lists each type's Z-move twice (physical=even id, special=odd id, ids 622-657);
    both share the same display name, so we keep the first one seen per type.
    """
    mrows = {r["id"]: r for r in dict_rows(os.path.join(CACHE, "moves.csv"))}
    ko_mv = ko_names("move_names", "move_id")
    out = {}
    for mid in range(622, 658):
        r = mrows.get(str(mid))
        if not r:
            continue
        eid = POKEAPI_TYPE.get(int(r["type_id"]))
        if eid is None or eid in out:
            continue
        name = ko_mv.get(str(mid))
        if name:
            out[eid] = name
    # Emit a stable, type-ordered list the engine can index by type id.
    return [{"type": eid, "nameKo": out[eid]} for eid in sorted(out)]


def resolve_form(slug, set_name, species_out, species_dex, used_abilities):
    """Resolve an alternate form to a species id, adding a species entry + pixel sprite if new.
    Base-form slugs (no dash) just reuse the existing base dex entry."""
    data = api_get("pokemon/%s" % slug)
    if not data:
        return None
    fid = data["id"]  # national dex for base forms, 10xxx for alternate forms
    if fid in species_out:
        return fid
    # types
    types = []
    for t in sorted(data["types"], key=lambda x: x["slot"]):
        tname = t["type"]["name"].capitalize()
        if tname in TYPE_ID:
            types.append(TYPE_ID[tname])
    # base stats -> engine order HP,Atk,Def,Spe,SpA,SpD
    bs = {s["stat"]["name"]: s["base_stat"] for s in data["stats"]}
    base = [bs.get("hp", 1), bs.get("attack", 1), bs.get("defense", 1), bs.get("speed", 1),
            bs.get("special-attack", 1), bs.get("special-defense", 1)]
    # abilities
    abil = []
    for a in data.get("abilities", []):
        aid = ABILITIES_CSV.get(a["ability"]["name"])
        if aid is not None:
            abil.append(aid); used_abilities.add(a["ability"]["name"])
    abil = list(dict.fromkeys(abil))
    weight = int(data.get("weight", 0))  # PokeAPI weight is already in 0.1 kg (hectograms)
    nameKo = FORM_NAME_KO.get(slug) or species_out.get(fid, {}).get("nameKo") or slug
    # gender ratio: inherit the base species' value if we have it, else default to 1:1 (127)
    base_dex = data.get("species", {}).get("url", "").rstrip("/").rsplit("/", 1)[-1]
    gr = species_out.get(int(base_dex) if base_dex.isdigit() else -1, {}).get("genderRatio", 127)
    species_out[fid] = {
        "id": fid, "name": slug, "nameKo": nameKo, "types": types, "base": base,
        "abilities": abil, "weight": weight, "genderRatio": gr,
        "final": True,  # forms don't evolve further in-battle
    }
    download_pixel_sprites(fid)
    return fid


# Resolved once in main() and shared with resolve_form.
ABILITIES_CSV = {}


def main():
    print("Reading Tree-Sets data from", TREE)
    # ---------- species (Pokedex TSV) ----------
    dex = dict_rows(os.path.join(TREE, "Battle Tree Lookup - Pokedex.tsv"), delim="\t")
    ko_species = ko_names("pokemon_species_names", "pokemon_species_id")
    ko_ability = ko_names("ability_names", "ability_id")
    abilities_csv = {r["identifier"]: int(r["id"]) for r in dict_rows(os.path.join(CACHE, "abilities.csv"))}
    ABILITIES_CSV.update(abilities_csv)  # shared with resolve_form()

    # Pre-evolution map: a species is NOT final if it is some other species' Pre-Evolution.
    pre_evos = set()
    for r in dex:
        pe = r.get("Pre-Evolution", "").strip()
        if pe and pe != "#N/A":
            pre_evos.add(pe)

    species_out = {}
    species_dex = {}  # English species name -> national dex id
    used_abilities = set()
    for r in dex:
        name = r["Species"].strip()
        forme = r.get("Forme", "").strip()
        t1 = r.get("Type1", "").strip()
        if not name or t1 not in TYPE_ID or not r.get("Ndex", "").strip().isdigit():
            continue  # blank/separator or alternate-forme row without a usable type
        ndex = int(r["Ndex"])
        species_dex.setdefault(name, ndex)
        # The TSV lists the base forme first, then Mega/alternate rows for the same Ndex. Keep only the
        # first (base) row per Ndex so base stats/types aren't overwritten by a Mega forme.
        if ndex in species_out:
            continue
        types = [TYPE_ID[t1]]
        t2 = r.get("Type2", "").strip()
        if t2 and t2 != "#N/A" and t2 in TYPE_ID:
            types.append(TYPE_ID[t2])
        abil = []
        for col in ("Ability1", "Ability2", "AbilityH"):
            a = r.get(col, "").strip()
            if a and a != "#N/A":
                slug = norm(a)
                aid = abilities_csv.get(slug)
                if aid is not None:
                    abil.append(aid); used_abilities.add(slug)
        # de-dupe abilities keeping order
        abil = list(dict.fromkeys(abil))
        # weight: "220.5 lbs." -> 0.1 kg units
        wm = re.search(r"[0-9]+(?:\.[0-9]+)?", r.get("Weight", "") or "")
        kg = (float(wm.group()) * 0.45359237) if wm else 0.0
        weight = int(round(kg * 10))
        # gender: %Female 0.125 -> Gen5 threshold out of 256; genderless if both blank
        male = r.get("%Male", "").strip(); female = r.get("%Female", "").strip()
        if male in ("", "#N/A") and female in ("", "#N/A"):
            gender_ratio = 255  # genderless
        else:
            fm = float(female) if female not in ("", "#N/A") else 0.0
            if fm >= 1.0:
                gender_ratio = 254
            elif fm <= 0.0:
                gender_ratio = 0
            else:
                gender_ratio = int(round(fm * 256))
        base = [int(r["HP"]), int(r["Attack"]), int(r["Defense"]),
                int(r["Speed"]), int(r["SpAttack"]), int(r["SpDefense"])]  # HP,Atk,Def,Spe,SpA,SpD
        species_out[ndex] = {
            "id": ndex, "name": name, "nameKo": ko_species.get(str(ndex), name),
            "types": types, "base": base, "abilities": abil, "weight": weight,
            "genderRatio": gender_ratio, "final": name not in pre_evos,
        }

    # ---------- Mega formes (dex rows whose Forme contains "Mega") keyed by base Ndex ----------
    # The Battle Tree pool has no Charizard/Mewtwo mega sets, so one mega forme per base species is
    # unambiguous. Each entry carries the mega's types/base stats/ability; the stone id is filled below.
    mega_by_species = {}
    for r in dex:
        forme = r.get("Forme", "").strip()
        if "Mega" not in forme:
            continue
        if not r.get("Ndex", "").strip().isdigit():
            continue
        ndex = int(r["Ndex"])
        mtypes = [TYPE_ID[r["Type1"].strip()]]
        mt2 = r.get("Type2", "").strip()
        if mt2 and mt2 != "#N/A" and mt2 in TYPE_ID:
            mtypes.append(TYPE_ID[mt2])
        ma = r.get("Ability1", "").strip()
        ma_id = abilities_csv.get(norm(ma)) if ma and ma != "#N/A" else None
        if ma_id is not None:
            used_abilities.add(norm(ma))
        mbase = [int(r["HP"]), int(r["Attack"]), int(r["Defense"]),
                 int(r["Speed"]), int(r["SpAttack"]), int(r["SpDefense"])]
        # Resolve the Mega form's own PokeAPI id and download its sprite (fallback to base species sprite).
        sprite_id = download_mega_sprite(r["Species"].strip())
        mega_by_species[ndex] = {
            "species": ndex, "stone": 0, "nameKo": "메가 " + ko_species.get(str(ndex), r["Species"].strip()),
            "types": mtypes, "base": mbase, "ability": ma_id if ma_id is not None else 0,
            "sprite": sprite_id or ndex,
        }

    # ---------- sets (Pokemon-Table) ----------
    set_rows = csv_rows(os.path.join(TREE, "Pokemon-Table 1.csv"), delim=";")
    natures_out, nature_eid = build_natures()
    items_csv = {r["identifier"]: int(r["id"]) for r in dict_rows(os.path.join(CACHE, "items.csv"))}
    moves_csv = {r["identifier"]: r for r in dict_rows(os.path.join(CACHE, "moves.csv"))}

    sets = []
    used_moves = {}   # slug -> move_id
    used_items = set()
    used_z = {}       # z-item id -> (type, nameKo)
    species_set_ids = {}  # dex id -> [set ids]
    cur = None
    sid = 0
    unknown = set()
    for row in set_rows[1:]:
        if not any(c.strip() for c in row):
            continue
        name = row[0].strip()
        if name:
            cur = name
        is_mega = row[1].strip().lower() == "mega"
        if cur in FORM_SETS:
            # A named alternate form: resolve (and cache) its own species entry; sets reference its id.
            dexid = resolve_form(FORM_SETS[cur], cur, species_out, species_dex, used_abilities)
            if dexid is None:
                unknown.add(cur); continue
        elif cur in species_dex:
            dexid = species_dex[cur]
        else:
            unknown.add(cur); continue
        nature_name = row[2].strip()
        nid = nature_eid.get(nature_name, 0)
        item_name = row[3].strip()
        item_id = 0
        if item_name in Z_CRYSTALS:
            zid, ztype, zko = Z_CRYSTALS[item_name]
            item_id = zid
            used_z[zid] = (ztype, zko)
        elif item_name:
            islug = norm(item_name)
            item_id = items_csv.get(islug, 0)
            if item_id:
                used_items.add(islug)
            # a Mega set's stone links the base species to its mega forme
            if is_mega and dexid in mega_by_species and item_id:
                mega_by_species[dexid]["stone"] = item_id
        move_ids = []
        for c in row[4:8]:
            mv = c.strip()
            if not mv:
                continue
            mslug = norm(mv)
            mrow = moves_csv.get(mslug)
            if mrow is None:
                unknown.add("move:" + mv); continue
            mid = int(mrow["id"])
            move_ids.append(mid)
            used_moves[mslug] = mid
        # EV placement columns 8..13 -> HP,Atk,Def,SpA,SpD,Spd (csv order); engine order HP,Atk,Def,Spe,SpA,SpD
        csv_ev = [bool(row[i].strip()) if i < len(row) else False for i in range(8, 14)]
        evs = [csv_ev[0], csv_ev[1], csv_ev[2], csv_ev[5], csv_ev[3], csv_ev[4]]  # -> HP,Atk,Def,Spe,SpA,SpD
        sets.append({
            "id": sid, "species": dexid, "moves": move_ids, "evs": evs,
            "nature": nid, "item": item_id, "form": 1 if is_mega else 0, "super": True,
        })
        species_set_ids.setdefault(dexid, []).append(sid)
        sid += 1

    megas_out = [m for m in mega_by_species.values() if m["stone"] != 0]

    # HP-triggered Zen Mode forme (Darmanitan, species 555) if it's in the pool.
    zen_out = []
    if 555 in species_set_ids:
        zdata = api_get("pokemon/darmanitan-zen")
        if zdata:
            ztypes = [TYPE_ID[t["type"]["name"].capitalize()] for t in sorted(zdata["types"], key=lambda x: x["slot"])
                      if t["type"]["name"].capitalize() in TYPE_ID]
            zbs = {s["stat"]["name"]: s["base_stat"] for s in zdata["stats"]}
            zen_out.append({
                "species": 555, "nameKo": "불비달마(달마모드)", "types": ztypes,
                "base": [zbs["hp"], zbs["attack"], zbs["defense"], zbs["speed"], zbs["special-attack"], zbs["special-defense"]],
                "sprite": zdata["id"],
            })
            download_pixel_sprites(zdata["id"])

    # HP-triggered Schooling forme (Wishiwashi, species 746): the School form appears while HP > 1/4
    # (level 20+), granting far higher stats. Modeled with the same reversible-forme mechanism as Zen
    # Mode but with whenLowHp=False and a 1/4 threshold.
    if 746 in species_set_ids:
        sdata = api_get("pokemon/wishiwashi-school")
        if sdata:
            stypes = [TYPE_ID[t["type"]["name"].capitalize()] for t in sorted(sdata["types"], key=lambda x: x["slot"])
                      if t["type"]["name"].capitalize() in TYPE_ID]
            sbs = {s["stat"]["name"]: s["base_stat"] for s in sdata["stats"]}
            zen_out.append({
                "species": 746, "nameKo": "약어리(군집)", "types": stypes,
                "base": [sbs["hp"], sbs["attack"], sbs["defense"], sbs["speed"], sbs["special-attack"], sbs["special-defense"]],
                "sprite": sdata["id"],
                "ability": 208, "whenLowHp": False, "hpNumer": 1, "hpDenom": 4, "minLevel": 20,
                "activateMsg": "군집을 이뤘다!",
            })
            download_pixel_sprites(sdata["id"])

    # Type-based Z-move display names (Gen 7). A damaging move used as a Z-move is announced under the
    # name of its type's Z-move (e.g. Fire -> 다이내믹풀플레임). Built from the PokeAPI CSV dumps so it
    # needs no network access. Z-move entries occupy ids 622-657 (physical/even, special/odd per type).
    zmoves_out = build_zmove_names()

    print("sets:", len(sets), "mega sets kept:", sum(1 for s in sets if s["form"] == 1),
          "mega formes:", len(megas_out), "zen:", len(zen_out), "z-crystals:", len(used_z),
          "z-move names:", len(zmoves_out), "species in pool:", len(species_set_ids))
    if unknown:
        print("UNRESOLVED (first 20):", sorted(unknown)[:20])

    # ---------- moves (enrich via PokeAPI) ----------
    ko_move = ko_names("move_names", "move_id")
    ko_move_desc = ko_flavor("move_flavor_text", "move_id")
    moves_out = []
    EXTRA = {"struggle": 165}
    for slug, mid in sorted({**used_moves, **EXTRA}.items(), key=lambda x: x[1]):
        moves_out.append(build_move(slug, mid, ko_move, ko_move_desc, moves_csv))
    print("moves:", len(moves_out))

    # ---------- items / abilities ----------
    ko_item = ko_names("item_names", "item_id")
    items_out = [{"id": 0, "key": "none", "name": "None", "nameKo": "없음", "desc": "",
                  "holdEffect": "NONE", "holdParam": 0, "flingPower": 0}]
    for zid, (ztype, zko) in sorted(used_z.items()):
        items_out.append({
            "id": zid, "key": "zcrystal_%d" % ztype, "name": zko, "nameKo": zko, "desc": "",
            "holdEffect": "Z_CRYSTAL", "holdParam": ztype, "flingPower": 0,
        })
    for slug in sorted(used_items, key=lambda s: items_csv.get(s, 0)):
        iid = items_csv[slug]
        items_out.append({
            "id": iid, "key": slug, "name": slug.replace("-", " ").title(),
            "nameKo": ko_item.get(str(iid), slug), "desc": "",
            "holdEffect": "NONE", "holdParam": 0, "flingPower": 0,
        })

    abil_out = []
    for slug in sorted(used_abilities, key=lambda s: abilities_csv.get(s, 0)):
        aid = abilities_csv[slug]
        abil_out.append({"id": aid, "key": slug, "name": slug.replace("-", " ").title(),
                         "nameKo": ko_ability.get(str(aid), slug), "desc": ""})

    # ---------- trainers ----------
    trainers_out, trainer_sprite_classes = build_trainers()

    # ---------- sprites ----------
    print("Downloading sprites for", len(species_set_ids), "species...")
    got = 0
    for dexid in sorted(species_set_ids):
        if download_sprites(dexid):
            got += 1
    # Mimikyu's Busted form sprite (shown after its Disguise breaks), if Mimikyu is in the pool.
    if 778 in species_set_ids:
        download_pixel_sprites(10143)
    print("sprite sets fetched:", got)

    # ---------- write ----------
    write("data/species.json", [species_out[d] for d in sorted(species_out) if d in species_set_ids])
    write("data/moves.json", moves_out)
    write("data/items.json", items_out)
    write("data/abilities.json", abil_out)
    write("data/natures.json", natures_out)
    write("data/types.json", build_types())
    write("data/sets.json", sets)
    write("data/trainers.json", trainers_out)
    write("data/megas.json", megas_out)
    write("data/zen.json", zen_out)
    write("data/zmoves.json", zmoves_out)
    print("DONE. species=%d moves=%d items=%d abilities=%d sets=%d trainers=%d megas=%d" % (
        len(species_set_ids), len(moves_out), len(items_out), len(abil_out), len(sets), len(trainers_out), len(megas_out)))


# ---- move effect derivation ----------------------------------------------------------
AILMENT_TO_EFFECT = {
    "paralysis": "STATUS_PARALYZE", "sleep": "STATUS_SLEEP", "freeze": "STATUS_FREEZE",
    "burn": "STATUS_BURN", "poison": "STATUS_POISON", "toxic": "STATUS_TOXIC",
    "confusion": "CONFUSE", "infatuation": "INFATUATE",
}
AILMENT_TO_INFLICT = {
    "paralysis": "PARALYSIS", "sleep": "SLEEP", "freeze": "FREEZE", "burn": "BURN",
    "poison": "POISON", "toxic": "TOXIC",
}
STAT_TO_KEY = {1: "ATK", 2: "DEF", 3: "SPE", 4: "SPA", 5: "SPD", 6: "ACC", 7: "EVA"}  # PokeAPI stat ids


def build_move(slug, mid, ko_move, ko_move_desc, moves_csv):
    api = api_get("move/%s" % slug)
    base = moves_csv.get(slug, {})
    # type
    if api and api.get("type"):
        tslug = api["type"]["name"].capitalize()
        tid = TYPE_ID.get(tslug, 0)
    else:
        tid = POKEAPI_TYPE.get(int(base.get("type_id", 1)), 0)
    # damage class
    dc = (api or {}).get("damage_class", {}).get("name") if api else None
    if not dc:
        dc = {1: "STATUS", 2: "PHYSICAL", 3: "SPECIAL"}.get(int(base.get("damage_class_id", 1)), "STATUS")
    category = {"physical": "PHYSICAL", "special": "SPECIAL", "status": "STATUS"}.get(dc, dc.upper())
    power = (api or {}).get("power") or (int(base["power"]) if base.get("power") else 0) or 0
    accuracy = (api or {}).get("accuracy")
    if accuracy is None:
        accuracy = int(base["accuracy"]) if base.get("accuracy") else 101  # None => never-miss
    pp = (api or {}).get("pp") or (int(base["pp"]) if base.get("pp") else 5)
    priority = (api or {}).get("priority")
    if priority is None:
        priority = int(base["priority"]) if base.get("priority") else 0
    meta = (api or {}).get("meta") or {}
    ailment = (meta.get("ailment") or {}).get("name")
    ailment_chance = meta.get("ailment_chance") or 0
    crit = meta.get("crit_rate") or 0
    flinch = meta.get("flinch_chance") or 0
    drain = meta.get("drain") or 0
    heal = meta.get("healing") or 0
    hits_min = (meta.get("min_hits") or 1) or 1
    hits_max = (meta.get("max_hits") or 1) or 1
    stat_changes = []
    for sc in (api or {}).get("stat_changes", []) or []:
        key = {"attack": "ATK", "defense": "DEF", "speed": "SPE", "special-attack": "SPA",
               "special-defense": "SPD", "accuracy": "ACC", "evasion": "EVA"}.get(sc["stat"]["name"])
        if key:
            stat_changes.append({"stat": key, "stages": sc["change"],
                                 "chance": (api.get("effect_chance") or 0) if category != "STATUS" else 0})
    inflict = AILMENT_TO_INFLICT.get(ailment, "NONE")
    inflict_chance = ailment_chance if inflict != "NONE" else 0
    # derive a coarse engine effect string
    cat = (meta.get("category") or {}).get("name", "")
    effect = derive_effect(slug, category, cat, ailment, drain, heal, stat_changes, meta)
    flags = derive_flags(api, category)
    # Whether a damaging move's stat changes apply to the USER rather than the target. PokeAPI's
    # meta category "damage-raise" means the user buffs/debuffs itself (Charge Beam +SpA, Overheat
    # -SpA, Close Combat -Def/-SpD, …); "damage-lower" lowers the target (Crunch, Psychic, …).
    stats_self = bool(stat_changes) and category != "STATUS" and cat == "damage-raise"
    nameKo = ko_move.get(str(mid), slug.replace("-", " ").title())
    # Korean move description: prefer the CSV flavor text, fall back to the cached PokeAPI entries.
    desc = ko_move_desc.get(str(mid), "")
    if not desc and api:
        kos = [e for e in (api.get("flavor_text_entries") or []) if (e.get("language") or {}).get("name") == "ko"]
        if kos:
            desc = " ".join((kos[-1].get("flavor_text") or "").split()).strip()
    return {
        "id": mid, "key": slug.replace("-", "_"), "name": slug.replace("-", " ").title(),
        "nameKo": nameKo, "desc": desc, "type": tid, "category": category, "power": power or 0,
        "accuracy": accuracy if accuracy is not None else 101, "pp": pp, "priority": priority,
        "hitsMin": hits_min, "hitsMax": hits_max, "inflict": inflict, "inflictChance": inflict_chance,
        "crit": crit, "flinch": flinch, "effect": effect, "drain": drain, "heal": heal,
        "target": derive_target(api), "stats": stat_changes, "statsSelf": stats_self, "flags": flags,
    }


def derive_target(api):
    t = (api or {}).get("target", {}).get("name", "selected-pokemon") if api else "selected-pokemon"
    return {
        "user": "USER", "users-field": "USER_SIDE", "user-and-allies": "USER_SIDE",
        "all-opponents": "ALL_FOES", "entire-field": "FIELD", "opponents-field": "FOE_SIDE",
        "all-pokemon": "ALL", "selected-pokemon": "SELECTED", "random-opponent": "RANDOM_FOE",
        "specific-move": "SELECTED", "all-other-pokemon": "ALL",
    }.get(t, "SELECTED")


def derive_effect(slug, category, meta_cat, ailment, drain, heal, stat_changes, meta):
    s = slug.replace("-", "_")
    # a handful of signature moves the engine knows by effect string
    named = {
        "high_horsepower": "", "foul_play": "FOUL_PLAY", "dragon_tail": "CIRCLE_THROW",
        "circle_throw": "CIRCLE_THROW", "roar": "FORCE_SWITCH", "whirlwind": "FORCE_SWITCH",
        "u_turn": "SWITCH_HIT", "volt_switch": "SWITCH_HIT", "rapid_spin": "RAPID_SPIN",
        "seismic_toss": "LEVEL_DAMAGE_FLAT", "night_shade": "LEVEL_DAMAGE_FLAT",
        "super_fang": "HALVE_HP", "endeavor": "SET_HP_EQUAL_TO_USER", "psywave": "RANDOM_DAMAGE_1_TO_150_LEVEL",
        "counter": "COUNTER", "mirror_coat": "MIRROR_COAT", "metal_burst": "METAL_BURST",
        "fissure": "ONE_HIT_KO", "guillotine": "ONE_HIT_KO", "horn_drill": "ONE_HIT_KO", "sheer_cold": "ONE_HIT_KO",
        "protect": "PROTECT", "detect": "PROTECT", "substitute": "SET_SUBSTITUTE", "rest": "REST",
        "encore": "ENCORE", "disable": "DISABLE", "taunt": "TAUNT", "torment": "TORMENT",
        "hi_jump_kick": "CRASH_ON_MISS", "high_jump_kick": "CRASH_ON_MISS", "jump_kick": "CRASH_ON_MISS",
        "sucker_punch": "HIT_FIRST_IF_TARGET_ATTACKING", "fake_out": "ALWAYS_FLINCH_FIRST_TURN_ONLY",
        "trick_room": "TRICK_ROOM", "tailwind": "TAILWIND", "stealth_rock": "STEALTH_ROCK",
        "electric_terrain": "TERRAIN_ELECTRIC", "grassy_terrain": "TERRAIN_GRASSY",
        "misty_terrain": "TERRAIN_MISTY", "psychic_terrain": "TERRAIN_PSYCHIC",
        "spikes": "SPIKES", "toxic_spikes": "TOXIC_SPIKES", "leech_seed": "LEECH_SEED",
        "will_o_wisp": "STATUS_BURN", "thunder_wave": "STATUS_PARALYZE", "toxic": "STATUS_TOXIC",
        "hidden_power": "RANDOM_POWER_BASED_ON_IVS", "weather_ball": "CHANGE_TYPE_WITH_WEATHER",
        "gyro_ball": "POWER_BASED_ON_LOW_SPEED", "electro_ball": "ELECTRO_BALL",
        "heavy_slam": "HEAVY_SLAM", "heat_crash": "HEAVY_SLAM", "grass_knot": "INCREASE_POWER_WITH_WEIGHT",
        "low_kick": "INCREASE_POWER_WITH_WEIGHT", "return": "POWER_BASED_ON_FRIENDSHIP",
        "frustration": "POWER_BASED_ON_LOW_FRIENDSHIP", "final_gambit": "FINAL_GAMBIT",
        "pursuit": "HIT_BEFORE_SWITCH", "fly": "FLY", "dig": "DIG", "dive": "DIVE",
        "solar_beam": "SKIP_CHARGE_TURN_IN_SUN", "focus_punch": "HIT_LAST_WHIFF_IF_HIT",
        "baton_pass": "PASS_STATS_AND_STATUS", "destiny_bond": "KO_MON_THAT_DEFEATED_USER",
        "future_sight": "HIT_IN_3_TURNS", "doom_desire": "HIT_IN_3_TURNS",
        # Trick / Switcheroo swap the two Pokémon's held items.
        "trick": "SWITCH_HELD_ITEMS", "switcheroo": "SWITCH_HELD_ITEMS",
        # self-KO moves: the user faints after dealing damage
        "explosion": "USER_FAINTS", "self_destruct": "USER_FAINTS", "misty_explosion": "USER_FAINTS",
        "memento": "USER_FAINTS_LOWER_FOE", "healing_wish": "USER_FAINTS_HEAL_SWITCHIN",
        # variable-power moves
        "flail": "INCREASE_POWER_WITH_LESS_HP", "reversal": "INCREASE_POWER_WITH_LESS_HP",
        "wring_out": "INCREASE_POWER_WITH_MORE_HP", "crush_grip": "INCREASE_POWER_WITH_MORE_HP",
        "punishment": "INCREASE_POWER_WITH_MORE_STAT_UP", "stored_power": "POWER_BY_USER_STAT_UP",
        "power_trip": "POWER_BY_USER_STAT_UP", "trump_card": "HIGHER_POWER_WHEN_LOW_PP",
        "natural_gift": "NATURAL_GIFT", "fling": "FLING",
        "eruption": "POWER_BY_USER_HP", "water_spout": "POWER_BY_USER_HP",
        "facade": "DOUBLE_POWER_WHEN_STATUSED",
        "revenge": "DOUBLE_POWER_IF_HIT", "avalanche": "DOUBLE_POWER_IF_HIT", "assurance": "DOUBLE_POWER_IF_HIT",
        "payback": "DOUBLE_POWER_IF_MOVING_SECOND",
        "venoshock": "DOUBLE_POWER_IF_TARGET_STATUSED", "hex": "DOUBLE_POWER_IF_TARGET_STATUSED",
        "brine": "DOUBLE_POWER_WHEN_BELOW_HALF",
        "smelling_salts": "DOUBLE_IF_TARGET_PARALYZED", "wake_up_slap": "DOUBLE_IF_TARGET_ASLEEP",
        # Dream Eater only works on a sleeping target (and drains HP); PokeAPI tags it as a drain move,
        # which made it hit any target. RECOVER_DAMAGE_SLEEP gates it on the target being asleep.
        "dream_eater": "RECOVER_DAMAGE_SLEEP",
        # Sleep Talk / Snore can only be used while the user is asleep. The engine gates both by these
        # effect strings; PokeAPI leaves them as plain status/flinch moves, so set them explicitly.
        "sleep_talk": "USE_RANDOM_LEARNED_MOVE_SLEEP", "snore": "DAMAGE_WHILE_ASLEEP",
        # ---- Healing moves. The engine has no generic "HEAL" effect; each heal is a specific effect
        # string. Without these, Recover/Roost/Synthesis/etc. silently fail.
        "recover": "RESTORE_HALF_HP", "slack_off": "RESTORE_HALF_HP", "soft_boiled": "RESTORE_HALF_HP",
        "milk_drink": "RESTORE_HALF_HP", "heal_order": "RESTORE_HALF_HP", "shore_up": "RESTORE_HALF_HP",
        "roost": "HEAL_HALF_REMOVE_FLYING_TYPE",
        "synthesis": "HEAL_HALF_MORE_IN_SUN", "moonlight": "HEAL_HALF_MORE_IN_SUN", "morning_sun": "HEAL_HALF_MORE_IN_SUN",
        "wish": "HEAL_IN_3_TURNS", "swallow": "SWALLOW", "stockpile": "STOCKPILE", "spit_up": "SPIT_UP",
        "belly_drum": "MAX_ATK_LOSE_HALF_MAX_HP",
        # ---- Field / self utility moves the engine already implements under specific effect strings.
        "ingrain": "GROUND_TRAP_USER_CONTINUOUS_HEAL", "aqua_ring": "RESTORE_HP_EVERY_TURN",
        "magnet_rise": "GIVE_GROUND_IMMUNITY", "power_trick": "SWAP_ATK_DEF",
        "reflect": "SET_REFLECT", "light_screen": "SET_LIGHT_SCREEN",
        "safeguard": "PREVENT_STATUS", "mist": "PREVENT_STAT_REDUCTION", "focus_energy": "CRIT_UP_2",
        "rain_dance": "WEATHER_RAIN", "sunny_day": "WEATHER_SUN", "sandstorm": "WEATHER_SANDSTORM", "hail": "WEATHER_HAIL",
        "haze": "RESET_STAT_CHANGES", "perish_song": "ALL_FAINT_3_TURNS", "curse": "CURSE",
        "nightmare": "STATUS_NIGHTMARE", "yawn": "STATUS_SLEEP_NEXT_TURN", "pain_split": "AVERAGE_HP",
        "foresight": "FORESIGHT", "odor_sleuth": "FORESIGHT", "mean_look": "PREVENT_ESCAPE", "block": "PREVENT_ESCAPE",
        "gastro_acid": "SUPRESS_ABILITY", "gravity": "GRAVITY", "psych_up": "COPY_STAT_CHANGES",
        "recycle": "RECYCLE", "grudge": "REMOVE_ALL_PP_ON_DEFEAT", "spite": "DECREASE_LAST_MOVE_PP",
        "aromatherapy": "HEAL_STATUS", "refresh": "HEAL_STATUS", "heal_bell": "HEAL_STATUS",
        "conversion2": "CONVERSION2", "acupressure": "RANDOM_STAT_UP_2", "metronome": "CALL_RANDOM_MOVE",
    }
    if s in named and named[s]:
        return named[s]
    if meta_cat == "ohko":
        return "ONE_HIT_KO"
    if category == "STATUS":
        if stat_changes:
            # a self-stat-up status move
            tgt_self = all(c["stages"] > 0 for c in stat_changes)
            return "STAT_CHANGE" if not tgt_self else "RAISE_STATS"
        if ailment in AILMENT_TO_EFFECT:
            return AILMENT_TO_EFFECT[ailment]
        if heal:
            return "HEAL"
        return "NONE"
    # damaging move with a secondary
    if drain > 0:
        return "DRAIN"
    if ailment in AILMENT_TO_EFFECT and (meta.get("ailment_chance") or 0) > 0:
        return "HIT_" + AILMENT_TO_EFFECT[ailment]
    if flinch_from(meta):
        return "FLINCH_HIT"
    if stat_changes:
        return "STAT_CHANGE_HIT"
    return "NONE"


def flinch_from(meta):
    return (meta.get("flinch_chance") or 0) > 0


def derive_flags(api, category):
    flags = []
    for fl in (api or {}).get("meta", {}) and [] or []:
        pass
    names = [f["name"] for f in (api or {}).get("flags", []) or []] if api else []
    # PokeAPI /move doesn't list flags under "flags"; use learnset-independent heuristics instead.
    # Contact: physical melee moves generally make contact; use PokeAPI "contact" via effect text is unreliable,
    # so approximate: physical moves make contact unless clearly ranged.
    RANGED = {"earthquake", "surf", "rock-slide", "flamethrower"}  # not exhaustive; only affects contact abilities
    return flags


CATEGORY_KO = {
    "Ace Trainer": "엘리트 트레이너", "Aether Foundation": "에테르재단", "Backpacker": "백팩커",
    "Battle Legend": "배틀 레전드", "Bellhop": "벨보이", "Black Belt": "검은 띠", "Captain": "캡틴",
    "Collector": "컬렉터", "Cook": "요리사", "Dancer": "댄서", "Firefighter": "소방관",
    "Gentleman": "신사", "Golfer": "골퍼", "Hiker": "등산가", "Janitor": "청소부", "Lass": "미니스커트",
    "Madame": "마담", "Office Worker": "회사원", "Pokémon Breeder": "포켓몬 브리더",
    "Pokémon Trainer": "포켓몬 트레이너", "Police Officer": "경찰관", "Preschooler": "유치원생",
    "Punk Girl": "펑크걸", "Punk Guy": "펑크가이", "Rising Star": "기대주", "Scientist": "연구원",
    "Sightseer": "관광객", "Veteran": "베테랑", "Worker": "작업자", "Youngster": "쇼트팬츠",
    "Youth Athlete": "운동부", "Pokémon Center Lady": "포켓몬센터 간호사", "Undiscovered": "트레이너",
}


def build_trainers():
    cats = [r["Trainer Category"].strip() for r in dict_rows(os.path.join(TREE, "Trainer Category-Table 1.csv"), delim=";")]
    cat_id = {c: i for i, c in enumerate(cats)}
    trainers = []
    for i, r in enumerate(dict_rows(os.path.join(TREE, "Trainer-Table 1.csv"), delim=";")):
        name = r["Name"].strip()
        # Some rows carry a trailing "?" in the Category (a source data quirk); normalize it off.
        cat = r.get("Category", "").strip().rstrip("?").strip() or "Undiscovered"
        start = int(r["Start"]) if r.get("Start", "").strip().isdigit() else 1
        end = int(r["End"]) if r.get("End", "").strip().isdigit() else 700
        trainers.append({
            "id": i, "trainerClass": cat_id.get(cat, 0), "classKey": cat, "className": cat,
            "classKo": CATEGORY_KO.get(cat, cat), "name": name, "sprite": cat_id.get(cat, 0), "sets": [],
            "start": start, "end": end,
        })
    return trainers, cat_id


def _pixel_urls(spr):
    """Static PIXEL sprites: default 2D front/back art; Gen7 pixel icon; BW fallbacks. All .png."""
    versions = spr.get("versions", {})
    bw = versions.get("generation-v", {}).get("black-white", {})
    icons = versions.get("generation-vii", {}).get("icons", {})
    front = spr.get("front_default") or bw.get("front_default")
    back = spr.get("back_default") or bw.get("back_default") or front
    icon = icons.get("front_default") or front
    return front, back, icon


def _save_sprites(fid, spr):
    front, back, icon = _pixel_urls(spr)
    ok = download(front, os.path.join(OUT, "sprites", "front", "%d.png" % fid))
    download(back, os.path.join(OUT, "sprites", "back", "%d.png" % fid))
    download(icon, os.path.join(OUT, "sprites", "icon", "%d.png" % fid))
    return ok


def download_pixel_sprites(fid):
    data = api_get("pokemon/%d" % fid)
    return _save_sprites(fid, data.get("sprites", {})) if data else False


def download_mega_sprite(species_name):
    """Fetch the Mega form's PokeAPI id and download its static pixel sprite. Returns the id or None."""
    data = api_get("pokemon/%s" % (norm(species_name) + "-mega"))
    if not data:
        return None
    fid = data.get("id")
    return fid if _save_sprites(fid, data.get("sprites", {})) else None


def download_sprites(dexid):
    """Static pixel front/back/icon from PokeAPI (front_default = classic 2D dot sprite)."""
    data = api_get("pokemon/%d" % dexid)
    return _save_sprites(dexid, data.get("sprites", {})) if data else False


if __name__ == "__main__":
    sys.exit(main())
