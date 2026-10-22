"""Genera el índice compacto de cartas y precios que descarga la app Android.

    python build_index.py index.json.gz

Lo corre .github/workflows/index.yml cada día y lo publica en el release fijo "index",
junto con auditoria.md (revisión de códigos contra Materia Hunter, ver auditoria.py).

Historial de precios: cada ejecución deja además history/AAAA-MM-DD.json.gz (junto al índice) con
los precios de mercado del día {productId: [normal, foil]}; el workflow lo sube al release "history".
Para calcular "h7"/"h30" se leen los snapshots de hace 7 y 30 días, primero de la carpeta local
history/ y si no están, del release público. Si el historial falla, el índice sale igual sin esos datos.
"""
from __future__ import annotations

import gzip
import io
import json
import math
import re
import sys
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from valorizar import CORRECTIONS, UA, build_index, code_key, load_catalog

HISTORY_URL = "https://github.com/Guillecc/fftcg-valorizador/releases/download/history/{}"
HISTORY_MAX = 5_000_000  # bytes de un snapshot comprimido (hoy pesan ~50 KB)

# Límites de cordura: si tcgcsv cae o cambia de formato, mejor no publicar que romper los teléfonos
MAX_PRICE = 100_000
MIN_CARDS, MAX_CARDS, MIN_SETS = 4000, 20000, 20
CONTROL_RE = re.compile(r"[\x00-\x1f\x7f-\x9f]")


def warn(msg: str) -> None:
    print(f"::warning::{msg}", file=sys.stderr)  # GitHub Actions lo muestra en el resumen


def num(v) -> float | None:
    """Precio válido (número finito, 0 < p <= 100.000) o None."""
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    return float(v) if math.isfinite(v) and 0 < v <= MAX_PRICE else None


def text(v, limit: int = 200) -> str:
    """Texto sin caracteres de control y con largo acotado."""
    return CONTROL_RE.sub("", str(v or ""))[:limit].strip()


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


# ---------------------------------------------------------------- historial

def read_snapshot(day: date, folder: Path) -> dict | None:
    """Snapshot {productId: [pn, pf]} de ese día (carpeta local o release "history"), o None."""
    name = f"{day.isoformat()}.json.gz"
    local = folder / name
    try:
        if local.exists():
            raw = local.read_bytes()
        else:
            req = urllib.request.Request(HISTORY_URL.format(name), headers=UA)
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read(HISTORY_MAX + 1)
            if len(raw) > HISTORY_MAX:
                raise ValueError("snapshot demasiado grande")
    except Exception:
        return None  # no existe (404) o no hay red: se prueba otro día
    try:
        data = json.loads(gzip.GzipFile(fileobj=io.BytesIO(raw)).read(50_000_000))  # tope al descomprimir
        return {int(pid): [num(v[0]), num(v[1])] for pid, v in data.items()}
    except Exception as ex:
        warn(f"snapshot {name} inválido, se ignora: {ex!r}")
        return None


def find_snapshot(target: date, folder: Path) -> tuple[str, dict] | None:
    """El snapshot más cercano a target: ese día o hasta 3 antes; si no hay, hasta 3 después."""
    for delta in (0, -1, -2, -3, 1, 2, 3):
        day = target + timedelta(days=delta)
        snap = read_snapshot(day, folder)
        if snap:
            return day.isoformat(), snap
    return None


def load_history(today: date, folder: Path) -> dict:
    """{"7": (fecha, snapshot), "30": ...} con los que se encontraron. Nunca lanza excepción."""
    found = {}
    for tag in ("7", "30"):
        try:
            hit = find_snapshot(today - timedelta(days=int(tag)), folder)
        except Exception as ex:
            hit = None
            warn(f"historial de {tag} días omitido: {ex!r}")
        if hit:
            found[tag] = hit
        else:
            warn(f"sin snapshot de hace {tag} días (±3); el índice sale sin h{tag}")
    return found


# ---------------------------------------------------------------- índice

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
            code = text(p["_number"], 1000)
            if len(code) > 40:
                warn(f"producto {pid}: código de {len(code)} caracteres, se omite")
                continue
            keys = [f"{k[0]}-{k[1]}" for k in map(code_key, code.split("/")) if k]
            card = {"id": pid, "c": code, "k": keys, "n": text(p["name"]), "g": p["groupId"],
                    "r": text(p["_rarity"], 40)}
            # Elemento ("Fire", o varios separados por ";": "Fire;Ice") y tipo (Forward, Backup...)
            ext = {e.get("name"): e.get("value") for e in p.get("extendedData", [])}
            for key, field in (("e", "Element"), ("t", "CardType")):
                if ext.get(field):
                    card[key] = text(ext[field], 40)
            # Por terminación: p = precio de mercado (ventas recientes), l = más bajo publicado
            # (null = sin stock o precio inválido). Si TCGplayer no tiene esa terminación, no van las claves.
            for sub, tag in (("Normal", "n"), ("Foil", "f")):
                row = rows.get((pid, sub))
                if row:
                    card["p" + tag] = num(row.get("marketPrice"))
                    card["l" + tag] = num(row.get("lowPrice"))
            cards.append(card)

    sets = {gid: [text(g["name"]), text(g["abbr"], 40)] for gid, g in catalog["groups"].items()}
    if not (MIN_CARDS <= len(cards) <= MAX_CARDS) or len(sets) < MIN_SETS:
        # Salir con error deja publicado el índice anterior (el workflow no llega a publicar)
        sys.exit(f"Índice rechazado: {len(cards)} cartas (esperado {MIN_CARDS}-{MAX_CARDS}), "
                 f"{len(sets)} sets (mínimo {MIN_SETS}). ¿tcgcsv cambió o está caído?")

    # Snapshot de hoy para el historial: solo precio de mercado, redondeado a centavos
    today = datetime.now(timezone.utc).date()
    snapshot = {str(c["id"]): [round(c["pn"], 2) if c.get("pn") else None,
                               round(c["pf"], 2) if c.get("pf") else None]
                for c in cards if c.get("pn") or c.get("pf")}

    # Cambio de precio: h7/h30 = [normal, foil] de mercado en esa fecha (solo si hay alguno)
    hist_dir = Path(out).resolve().parent / "history"
    hist = load_history(today, hist_dir)
    for tag, (_, snap) in hist.items():
        for card in cards:
            old = snap.get(card["id"])
            if old and (old[0] is not None or old[1] is not None):
                card["h" + tag] = old

    data = {
        "updated": today.isoformat(),
        "sets": sets,
        "cards": sorted(cards, key=lambda c: c["id"]),
        "hist": {"d" + tag: day for tag, (day, _) in hist.items()},  # fechas reales de h7/h30
        "fixes": {str(pid): code for pid, code in sorted(applied.items())},  # trazabilidad; la app no lo usa
    }
    with gzip.open(out, "wt", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    hist_dir.mkdir(exist_ok=True)
    snap_path = hist_dir / f"{today.isoformat()}.json.gz"
    with gzip.open(snap_path, "wt", encoding="utf-8") as f:
        json.dump(snapshot, f, separators=(",", ":"), allow_nan=False)
    print(f"{len(cards)} cartas, {len(sets)} sets, {len(applied)} correcciones automáticas, "
          f"historial {data['hist'] or 'sin datos'} -> {out} (+ history/{snap_path.name})")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "index.json.gz")
