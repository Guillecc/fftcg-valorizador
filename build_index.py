"""Genera el índice compacto de cartas y precios que descarga la app Android.

    python build_index.py index.json.gz

Lo corre .github/workflows/index.yml cada día y lo publica en el release fijo "index",
junto con auditoria.md (revisión de códigos contra Materia Hunter, ver auditoria.py).
"""
from __future__ import annotations

import gzip
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from valorizar import CORRECTIONS, build_index, code_key, load_catalog


def warn(msg: str) -> None:
    print(f"::warning::{msg}", file=sys.stderr)  # GitHub Actions lo muestra en el resumen


def run_audit(catalog: dict, report: Path) -> dict:
    """Correcciones automáticas {productId: código}. Si algo falla, {} y el índice sale como siempre."""
    auto, rows, info = {}, [], {}
    try:
        from auditoria import auditar
        auto, rows, info = auditar(catalog)
        if info.get("blocked"):
            warn(f"auditoría: {info['blocked']}")
    except Exception as ex:
        warn(f"auditoría omitida, solo correcciones manuales: {ex!r}")
        auto, rows, info = {}, [], {"error": repr(ex)}
    applied = {pid: code for pid, code in auto.items() if pid not in CORRECTIONS}
    try:
        from auditoria import write_report
        write_report(report, info, applied, rows, CORRECTIONS)
    except Exception as ex:
        warn(f"no se pudo escribir {report.name}: {ex!r}")
    return applied


def main(out: str) -> None:
    catalog = load_catalog(refresh=False)
    applied = run_audit(catalog, Path(out).with_name("auditoria.md"))
    try:
        index, _ = build_index(catalog, applied)
    except Exception as ex:
        warn(f"correcciones automáticas descartadas: {ex!r}")
        applied = {}
        index, _ = build_index(catalog)
    rows = {(r["productId"], r["subTypeName"]): r for r in catalog["prices"]}

    cards, seen = [], set()
    for products in index.values():
        for p in products:
            pid = p["productId"]
            if pid in seen:
                continue
            seen.add(pid)
            keys = [f"{k[0]}-{k[1]}" for k in map(code_key, p["_number"].split("/")) if k]
            card = {"id": pid, "c": p["_number"], "k": keys, "n": p["name"], "g": p["groupId"], "r": p["_rarity"]}
            # Por terminación: p = precio de mercado (ventas recientes), l = más bajo publicado
            # (null = sin stock). Si TCGplayer no tiene esa terminación, no se incluyen las claves.
            for sub, tag in (("Normal", "n"), ("Foil", "f")):
                row = rows.get((pid, sub))
                if row:
                    card["p" + tag] = row.get("marketPrice")
                    card["l" + tag] = row.get("lowPrice")
            cards.append(card)

    data = {
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "sets": {gid: [g["name"], g["abbr"]] for gid, g in catalog["groups"].items()},
        "cards": sorted(cards, key=lambda c: c["id"]),
        "fixes": {str(pid): code for pid, code in sorted(applied.items())},  # trazabilidad; la app no lo usa
    }
    with gzip.open(out, "wt", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(cards)} cartas, {len(data['sets'])} sets, {len(applied)} correcciones automáticas -> {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "index.json.gz")
