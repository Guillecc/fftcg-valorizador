"""Genera el índice compacto de cartas y precios que descarga la app Android.

    python build_index.py index.json.gz

Lo corre .github/workflows/index.yml cada día y lo publica en el release fijo "index".
"""
from __future__ import annotations

import gzip
import json
import sys
from datetime import datetime, timezone

from valorizar import build_index, code_key, load_catalog


def main(out: str) -> None:
    catalog = load_catalog(refresh=False)
    index, prices = build_index(catalog)

    cards, seen = [], set()
    for products in index.values():
        for p in products:
            pid = p["productId"]
            if pid in seen:
                continue
            seen.add(pid)
            keys = [f"{k[0]}-{k[1]}" for k in map(code_key, p["_number"].split("/")) if k]
            cards.append({
                "id": pid,
                "c": p["_number"],
                "k": keys,
                "n": p["name"],
                "g": p["groupId"],
                "r": p["_rarity"],
                "pn": prices.get((pid, "Normal")),
                "pf": prices.get((pid, "Foil")),
            })

    data = {
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "sets": {gid: [g["name"], g["abbr"]] for gid, g in catalog["groups"].items()},
        "cards": sorted(cards, key=lambda c: c["id"]),
    }
    with gzip.open(out, "wt", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(cards)} cartas, {len(data['sets'])} sets -> {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "index.json.gz")
