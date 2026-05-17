#!/usr/bin/env python3
"""
Generates pokemon_slots.json bundled with the app.
Run once: python scripts/generate_pokemon_slots.py
Output:   app/src/main/res/raw/pokemon_slots.json
Requires: pip install requests
"""

import json
import time
import requests

POKEAPI = "https://pokeapi.co/api/v2"
TCG_API = "https://api.pokemontcg.io/v2"
OUT_PATH = "app/src/main/res/raw/pokemon_slots.json"

# G-Max forms with visually distinct designs (not just oversized base forms)
GMAX_DISTINCT = {
    "charizard", "butterfree", "pikachu", "meowth", "machamp", "gengar",
    "kingler", "lapras", "eevee", "snorlax", "garbodor", "melmetal",
    "corviknight", "orbeetle", "drednaw", "coalossal", "flapple", "appletun",
    "sandaconda", "toxtricity", "centiskorch", "hatterene", "grimmsnarl",
    "alcremie", "copperajah", "duraludon", "urshifu"
}

# All Mega Evolutions (variety names as they appear in PokéAPI)
MEGA_VARIETIES = [
    "venusaur-mega", "charizard-mega-x", "charizard-mega-y", "blastoise-mega",
    "beedrill-mega", "pidgeot-mega", "slowbro-mega", "gengar-mega",
    "kangaskhan-mega", "pinsir-mega", "gyarados-mega", "aerodactyl-mega",
    "mewtwo-mega-x", "mewtwo-mega-y", "ampharos-mega", "scizor-mega",
    "heracross-mega", "houndoom-mega", "tyranitar-mega", "blaziken-mega",
    "gardevoir-mega", "mawile-mega", "aggron-mega", "medicham-mega",
    "manectric-mega", "banette-mega", "absol-mega", "garchomp-mega",
    "lucario-mega", "abomasnow-mega", "alakazam-mega", "steelix-mega",
    "sceptile-mega", "swampert-mega", "sableye-mega", "sharpedo-mega",
    "camerupt-mega", "altaria-mega", "glalie-mega", "salamence-mega",
    "metagross-mega", "latias-mega", "latios-mega", "rayquaza-mega",
    "lopunny-mega", "gallade-mega", "audino-mega", "diancie-mega"
]


def tcg_has_card(query: str) -> bool:
    """Returns True if pokemontcg.io has at least one card matching the query."""
    try:
        r = requests.get(f"{TCG_API}/cards", params={"q": query, "pageSize": 1}, timeout=10)
        data = r.json()
        return data.get("totalCount", 0) > 0
    except Exception:
        return False


def fetch_all_base_pokemon() -> list:
    """Fetches all 1025 base Pokémon from PokéAPI."""
    slots = []
    r = requests.get(f"{POKEAPI}/pokemon?limit=1025&offset=0", timeout=30).json()
    for i, p in enumerate(r["results"], start=1):
        slots.append({
            "id": p["name"],
            "name": p["name"].replace("-", " ").title(),
            "dex_number": i,
            "dex_order": i,
            "slot_type": "base"
        })
        if i % 100 == 0:
            print(f"  Fetched base {i}/1025...")
    return slots


def fetch_regional_variants(base_slots: list) -> list:
    """
    For each base Pokémon, checks for regional/form variants.
    Only includes a variant if pokemontcg.io has at least one card for it.
    """
    variants = []
    regional_keywords = ["alola", "galar", "hisui", "paldea"]
    order = 1026

    for slot in base_slots:
        try:
            r = requests.get(f"{POKEAPI}/pokemon-species/{slot['id']}", timeout=10).json()
        except Exception:
            continue

        varieties = r.get("varieties", [])
        for v in varieties:
            vname = v["pokemon"]["name"]
            if vname == slot["id"]:
                continue
            if not any(kw in vname for kw in regional_keywords):
                continue
            display = vname.replace("-", " ").title()
            query = f'name:"{display}"'
            if tcg_has_card(query):
                variants.append({
                    "id": vname,
                    "name": display,
                    "dex_number": slot["dex_number"],
                    "dex_order": order,
                    "slot_type": "regional"
                })
                print(f"  Regional variant with card: {vname}")
                order += 1
            time.sleep(0.05)

    return variants


def build_mega_slots(start_order: int) -> list:
    """Returns slots for all Mega Evolutions."""
    slots = []
    for i, mega_id in enumerate(MEGA_VARIETIES):
        name = mega_id.replace("-", " ").title()
        try:
            r = requests.get(f"{POKEAPI}/pokemon/{mega_id}", timeout=10).json()
            dex_number = r.get("id", 0)
        except Exception:
            dex_number = 0
        slots.append({
            "id": mega_id,
            "name": name,
            "dex_number": dex_number,
            "dex_order": start_order + i,
            "slot_type": "mega"
        })
        print(f"  Mega: {mega_id}")
        time.sleep(0.05)
    return slots


def build_gmax_slots(start_order: int) -> list:
    """Returns slots for G-Max forms with visually distinct designs."""
    slots = []
    order = start_order
    for base_name in sorted(GMAX_DISTINCT):
        gmax_id = f"{base_name}-gmax"
        try:
            r = requests.get(f"{POKEAPI}/pokemon/{gmax_id}", timeout=10).json()
            dex_number = r.get("id", 0)
        except Exception:
            dex_number = 0
        slots.append({
            "id": gmax_id,
            "name": f"{base_name.title()} (Gigantamax)",
            "dex_number": dex_number,
            "dex_order": order,
            "slot_type": "gmax"
        })
        print(f"  GMax: {gmax_id}")
        order += 1
        time.sleep(0.05)
    return slots


def main():
    import os
    os.makedirs("app/src/main/res/raw", exist_ok=True)

    print("Fetching base Pokémon...")
    base = fetch_all_base_pokemon()

    print("Checking regional variants against pokemontcg.io...")
    regional = fetch_regional_variants(base)

    print("Building Mega Evolution slots...")
    mega_start = (regional[-1]["dex_order"] + 1) if regional else 1026
    megas = build_mega_slots(mega_start)

    print("Building G-Max slots...")
    gmax_start = (megas[-1]["dex_order"] + 1) if megas else mega_start
    gmax = build_gmax_slots(gmax_start)

    all_slots = base + regional + megas + gmax
    print(f"\nTotal slots: {len(all_slots)}")
    print(f"  Base: {len(base)}, Regional: {len(regional)}, Mega: {len(megas)}, GMax: {len(gmax)}")

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(all_slots, f, indent=2, ensure_ascii=False)

    print(f"Written to {OUT_PATH}")


if __name__ == "__main__":
    main()
