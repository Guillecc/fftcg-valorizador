"""Valorizador de colección de Final Fantasy TCG con precios de TCGplayer (vía tcgcsv.com).

Uso:
    python valorizar.py                       # lee coleccion.csv
    python valorizar.py mi_lista.csv --fx 950 # otra lista y tipo de cambio fijo
    python valorizar.py --refresh             # vuelve a descargar los precios de hoy

Solo usa la librería estándar de Python (3.10+).
"""
from __future__ import annotations

import argparse
import csv
import html
import json
import re
import sys
import urllib.request
from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from pathlib import Path

BASE = "https://tcgcsv.com/tcgplayer/24"  # categoría 24 = Final Fantasy TCG
FX_URL = "https://mindicador.cl/api/dolar"  # dólar observado (Banco Central de Chile)
HERE = Path(__file__).resolve().parent
UA = {"User-Agent": "FFTCG-Valorizador/1.0"}

CODE_RE = re.compile(r"^([A-Z0-9]+)-0*(\d+)([A-Z]?)$")


@dataclass
class Row:
    codigo: str
    cantidad: int
    foil: bool
    version: str = ""
    nombre: str = ""
    set: str = ""
    rareza: str = ""
    precio_usd: float | None = None
    total_usd: float | None = None
    total_clp: int | None = None
    product_id: int | None = None
    url: str = ""
    nota: str = ""
    otras_versiones: list[str] = field(default_factory=list)


# ---------------------------------------------------------------- descarga

def get_json(url: str):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def load_catalog(refresh: bool) -> dict:
    """Descarga sets, cartas y precios (una vez al día; queda en cache/AAAA-MM-DD.json)."""
    cache = HERE / "cache" / f"{date.today().isoformat()}.json"
    if cache.exists() and not refresh:
        return json.loads(cache.read_text(encoding="utf-8"))

    groups = get_json(f"{BASE}/groups")["results"]
    catalog = {"groups": {}, "products": [], "prices": []}
    for i, g in enumerate(groups, 1):
        gid = g["groupId"]
        print(f"  [{i}/{len(groups)}] {g['name']}", file=sys.stderr)
        catalog["groups"][str(gid)] = {"name": g["name"], "abbr": g.get("abbreviation") or ""}
        catalog["products"] += get_json(f"{BASE}/{gid}/products")["results"]
        catalog["prices"] += get_json(f"{BASE}/{gid}/prices")["results"]

    cache.parent.mkdir(exist_ok=True)
    cache.write_text(json.dumps(catalog), encoding="utf-8")
    return catalog


def get_fx() -> float:
    serie = get_json(FX_URL)["serie"]
    return float(serie[0]["valor"])


# ---------------------------------------------------------------- índice

def code_key(code: str) -> tuple[str, int] | None:
    """'29-087L' -> ('29', 87). Ignora ceros a la izquierda y la letra de rareza."""
    m = CODE_RE.match(re.sub(r"-+", "-", code.strip().upper()))  # hay datos con "PR--161"
    return (m.group(1), int(m.group(2))) if m else None


def set_matches(prefix: str, abbr: str) -> bool:
    """El prefijo del código ('1', '29', 'PR') coincide con la abreviatura del set ('OP1', '29', 'PR')."""
    abbr = abbr.upper()
    return abbr in (prefix, f"OP{prefix}")


def build_index(catalog: dict) -> tuple[dict, dict]:
    """index[(set, nro)] -> lista de productos; prices[(productId, subtipo)] -> precio."""
    index: dict[tuple[str, int], list[dict]] = {}
    for p in catalog["products"]:
        ext = {e["name"]: e["value"] for e in p.get("extendedData", [])}
        number = ext.get("Number")
        if not number:
            continue  # productos sellados (boosters, cajas)
        p["_number"], p["_rarity"] = number, ext.get("Rarity", "")
        # Las reimpresiones traen ambos códigos: '29-001R/26-003R'
        for part in number.split("/"):
            key = code_key(part)
            if key:
                index.setdefault(key, []).append(p)

    prices = {}
    for pr in catalog["prices"]:
        price = pr.get("marketPrice") or pr.get("midPrice")
        if price is not None:
            prices[(pr["productId"], pr["subTypeName"])] = price
    return index, prices


def choose(cands: list[dict], key: tuple[str, int], version: str, groups: dict):
    """Elige la impresión. Devuelve (producto | None, nota)."""
    if version:
        if version.isdigit():
            hits = [p for p in cands if p["productId"] == int(version)]
        else:
            hits = [p for p in cands if version.lower() in p["name"].lower()]
        if not hits:
            return None, f"ninguna versión contiene '{version}'"
        cands = hits

    # Preferir el set al que pertenece el código (no la reimpresión en otro set)
    own = [p for p in cands if set_matches(key[0], groups[str(p["groupId"])]["abbr"])]
    cands = own or cands
    # La versión base suele tener el nombre más corto ("Zidane" vs "Zidane (Full Art)")
    cands = sorted(cands, key=lambda p: (len(p["name"]), p["productId"]))
    nota = ""
    if len(cands) > 1 and not version:
        nota = f"{len(cands)} versiones; se usó la de nombre más corto (elige otra con la columna 'version')"
    return cands[0], nota


# ---------------------------------------------------------------- valorización

def read_collection(path: Path) -> list[Row]:
    rows = []
    with path.open(encoding="utf-8-sig", newline="") as f:
        for line in csv.DictReader(f):
            line = {k.strip().lower(): (v or "").strip() for k, v in line.items() if k}
            code = line.get("codigo", "")
            if not code or code.startswith("#"):
                continue
            rows.append(Row(
                codigo=code.upper(),
                cantidad=int(line.get("cantidad") or 1),
                foil=line.get("foil", "").lower() in ("si", "sí", "s", "yes", "y", "1", "x", "foil"),
                version=line.get("version", ""),
            ))
    return rows


def valorize(rows: list[Row], catalog: dict, fx: float) -> None:
    index, prices = build_index(catalog)
    groups = catalog["groups"]
    for r in rows:
        key = code_key(r.codigo)
        if not key:
            r.nota = "código con formato no reconocido (ej: 1-001H, PR-029)"
            continue
        cands = index.get(key)
        if not cands:
            r.nota = "código no encontrado en TCGplayer"
            continue
        p, r.nota = choose(cands, key, r.version, groups)
        if p is None:
            continue
        r.nombre, r.rareza, r.product_id, r.url = p["name"], p["_rarity"], p["productId"], p["url"]
        r.set = groups[str(p["groupId"])]["name"]
        r.otras_versiones = sorted({c["name"] for c in cands if c is not p and c["name"] != p["name"]})

        sub = "Foil" if r.foil else "Normal"
        price = prices.get((p["productId"], sub))
        if price is None:
            r.nota = "; ".join(filter(None, [r.nota, f"sin precio {sub} para esta versión"]))
            continue
        r.precio_usd = price
        r.total_usd = round(price * r.cantidad, 2)
        r.total_clp = round(r.total_usd * fx)


# ---------------------------------------------------------------- salida

def write_csv(rows: list[Row], path: Path) -> None:
    cols = [k for k in asdict(rows[0]).keys()] if rows else []
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for r in rows:
            d = asdict(r)
            d["foil"] = "sí" if r.foil else "no"
            d["otras_versiones"] = " | ".join(r.otras_versiones)
            w.writerow(d)


def write_html(rows: list[Row], path: Path, fx: float, total_usd: float, top: int) -> None:
    e = html.escape
    priced = sorted((r for r in rows if r.total_usd is not None), key=lambda r: -r.total_usd)
    missing = [r for r in rows if r.total_usd is None]

    def tr(r: Row) -> str:
        link = f'<a href="{e(r.url)}" target="_blank" rel="noopener">TCGplayer ↗</a>' if r.url else ""
        price = f"{r.precio_usd:,.2f}" if r.precio_usd is not None else "—"
        tot = f"{r.total_usd:,.2f}" if r.total_usd is not None else "—"
        clp = f"{r.total_clp:,}".replace(",", ".") if r.total_clp is not None else "—"
        return (f"<tr><td>{e(r.codigo)}</td><td>{e(r.nombre)}{' ✦' if r.foil else ''}</td>"
                f"<td>{e(r.set)}</td><td class=n>{r.cantidad}</td><td class=n>{price}</td>"
                f"<td class=n>{tot}</td><td class=n>{clp}</td><td>{link}</td><td class=note>{e(r.nota)}</td></tr>")

    head = ("<tr><th>Código</th><th>Carta</th><th>Set</th><th>Cant.</th><th>USD c/u</th>"
            "<th>Total USD</th><th>Total CLP</th><th>Últimas ventas</th><th>Nota</th></tr>")
    clp_total = f"{round(total_usd * fx):,}".replace(",", ".")
    page = f"""<!doctype html><html lang=es><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>Colección FFTCG</title>
<style>
:root{{--bg:#fff;--fg:#1a1a1a;--mut:#666;--line:#e3e3e3;--acc:#2456c9}}
@media (prefers-color-scheme:dark){{:root{{--bg:#16181d;--fg:#e8e8e8;--mut:#9a9a9a;--line:#2c2f36;--acc:#7aa2ff}}}}
body{{font:14px/1.4 system-ui,sans-serif;margin:0 auto;padding:16px;max-width:1100px;background:var(--bg);color:var(--fg)}}
h1{{margin:.2em 0}} .kpi{{display:flex;gap:24px;flex-wrap:wrap;margin:12px 0 20px}}
.kpi div{{font-size:13px;color:var(--mut)}} .kpi b{{display:block;font-size:24px;color:var(--fg)}}
.wrap{{overflow-x:auto}} table{{border-collapse:collapse;width:100%}}
td,th{{padding:6px 8px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}}
.n{{text-align:right;font-variant-numeric:tabular-nums}} .note{{color:var(--mut);white-space:normal}}
a{{color:var(--acc)}} small{{color:var(--mut)}}
</style>
<h1>Colección Final Fantasy TCG</h1>
<small>Precio de mercado TCGplayer vía tcgcsv.com · {datetime.now():%Y-%m-%d %H:%M} · 1 USD = {fx:,.2f} CLP</small>
<div class=kpi><div>Total USD<b>{total_usd:,.2f}</b></div><div>Total CLP<b>{clp_total}</b></div>
<div>Cartas<b>{sum(r.cantidad for r in rows)}</b></div><div>Sin precio<b>{len(missing)}</b></div></div>
<h2>Top {top} más valiosas</h2><div class=wrap><table>{head}{''.join(tr(r) for r in priced[:top])}</table></div>
<h2>Toda la colección</h2><div class=wrap><table>{head}{''.join(tr(r) for r in priced + missing)}</table></div>
<p><small>“Market price” es un promedio de ventas recientes. Para confirmar, abre el link y mira “Latest Sales”.</small></p>
</html>"""
    path.write_text(page, encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Valoriza una colección de Final Fantasy TCG con precios de TCGplayer.")
    ap.add_argument("coleccion", nargs="?", default=str(HERE / "coleccion.csv"), help="CSV: codigo,cantidad,foil[,version]")
    ap.add_argument("--fx", type=float, help="CLP por USD (por defecto: dólar observado de mindicador.cl)")
    ap.add_argument("--top", type=int, default=10, help="cuántas cartas mostrar en el top")
    ap.add_argument("--refresh", action="store_true", help="volver a descargar precios aunque haya caché de hoy")
    args = ap.parse_args()
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")  # tildes en la consola de Windows

    rows = read_collection(Path(args.coleccion))
    if not rows:
        sys.exit("La colección está vacía.")

    fx = args.fx
    if fx is None:
        try:
            fx = get_fx()
        except Exception as ex:  # sin internet o API caída
            sys.exit(f"No pude obtener el dólar ({ex}). Usa --fx, por ejemplo --fx 950")

    print("Cargando precios de TCGplayer…", file=sys.stderr)
    valorize(rows, load_catalog(args.refresh), fx)

    total_usd = round(sum(r.total_usd or 0 for r in rows), 2)
    out = HERE / "resultados"
    out.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M")
    csv_path, html_path = out / f"valorizacion_{stamp}.csv", out / f"valorizacion_{stamp}.html"
    write_csv(rows, csv_path)
    write_html(rows, html_path, fx, total_usd, args.top)

    priced = sorted((r for r in rows if r.total_usd is not None), key=lambda r: -r.total_usd)
    clp = f"{round(total_usd * fx):,}".replace(",", ".")
    print(f"\nTotal: USD {total_usd:,.2f}  |  CLP {clp}  (1 USD = {fx:.2f} CLP)")
    print(f"\nTop {args.top}:")
    for r in priced[:args.top]:
        print(f"  {r.codigo:<10} {r.nombre[:32]:<32} {'foil' if r.foil else '    '} x{r.cantidad:<3} USD {r.total_usd:>8.2f}")
    for r in rows:
        if r.nota:
            print(f"  ! {r.codigo}: {r.nota}")
    print(f"\nCSV:  {csv_path}\nHTML: {html_path}")


if __name__ == "__main__":
    main()
