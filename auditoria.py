"""Revisión diaria de los códigos que publica TCGplayer, contra la base comunitaria Materia Hunter.

Solo corrige sola los casos de alta confianza; el resto queda en el reporte (auditoria.md) para
revisarlo a mano. Nunca usa el mapeo productId -> código de Materia Hunter para corregir
(se vio que copia errores de TCGplayer): solo su lista código -> nombre.
"""
from __future__ import annotations

import difflib
import json
import os
import re
import unicodedata
import urllib.request
from datetime import date
from pathlib import Path

from valorizar import code_key

REF_BASE = os.environ.get("AUDITORIA_REF_BASE", "https://storage.googleapis.com/materiahunter-prod.appspot.com/json")
REF_CARDS = "/en/cardsV2.json"  # código -> nombre (inglés)
REF_VARIANTS = "/card-variantsV2.json"  # reimpresiones: serial <-> altSerial (PR-, Re-, W24-, TR-)
UA = {"User-Agent": "FFTCGValor/1.0"}
MAX_AUTO = 25  # más que esto huele a referencia rota: no se aplica ninguna
WINDOW = 5  # distancia máxima entre el número de TCGplayer y el sugerido (mismo set)

CODE_IN_NAME = re.compile(
    r"(?<![A-Za-z0-9])(PR-*\d{1,3}|Re-\d{1,3}[A-Z]?|TR-\d{1,3}[A-Z]?|W\d+-\d{1,3}[A-Z]?|\d{1,2}-\d{1,3}[A-Z]?)(?![A-Za-z0-9])",
    re.I)
CODE_SUFFIX = re.compile(r"\s+-\s+(PR|Re|TR|W\d+|\d{1,2})-*\d{1,3}[A-Z]?(/\S+)?\s*$", re.I)  # "Zack - PR071/007H"
PAREN_SUFFIX = re.compile(r"\s*\([^()]*\)\s*$")
EX_LB_SUFFIX = re.compile(r"\s+(EX|LB)$")  # "Arc EX", "Ace LB": la referencia no lo incluye en el nombre


# ---------------------------------------------------------------- referencia

def _get(url: str):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def fetch_reference(cache_dir: Path | None = None) -> tuple[list, list]:
    """Descarga los dos JSON de Materia Hunter (2 requests). Con cache_dir, una vez al día."""
    cache = cache_dir / f"materiahunter-{date.today().isoformat()}.json" if cache_dir else None
    if cache and cache.exists():
        ref = json.loads(cache.read_text(encoding="utf-8"))
        cards, variants = ref["cards"], ref["variants"]
    else:
        cards, variants = _get(REF_BASE + REF_CARDS), _get(REF_BASE + REF_VARIANTS)
    _check_shape(cards, variants)
    if cache and not cache.exists():
        cache.parent.mkdir(exist_ok=True)
        cache.write_text(json.dumps({"cards": cards, "variants": variants}), encoding="utf-8")
    return cards, variants


def _check_shape(cards, variants) -> None:
    ok = (isinstance(cards, list) and isinstance(variants, list) and len(cards) >= 1000 and len(variants) >= 1000
          and all(isinstance(c, dict) and isinstance(c.get("serial"), str) and isinstance(c.get("name"), str) for c in cards)
          and all(isinstance(v, dict) and isinstance(v.get("serial"), str) and isinstance(v.get("altSerial") or "", str)
                  for v in variants))
    if not ok:
        raise ValueError("la referencia de Materia Hunter cambió de formato o viene incompleta")


def akey(code: str) -> tuple[str, int] | None:
    """Como code_key, pero tolera 'PR071' y 'P-059' (= PR-059)."""
    s = re.sub(r"-+", "-", code.strip().upper())
    s = re.sub(r"^PR(?=\d)", "PR-", s)
    s = re.sub(r"^P-", "PR-", s)
    return code_key(s)


def norm(s: str) -> str:
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).lower().replace("&", " and ")
    return re.sub(r"[^a-z0-9]", "", s)


class Reference:
    def __init__(self, cards: list, variants: list):
        self.names: dict[tuple, set[str]] = {}  # código -> nombres normalizados
        self.serial: dict[tuple, str] = {}  # código -> como lo escribe la referencia ('PR-158')
        self.pairs: dict[tuple, set[tuple]] = {}  # reimpresión <-> original
        self.unnamed: set[tuple] = set()  # Crystal tokens y similares: sin nombre, no se pueden validar
        by_serial = {}
        for c in cards:
            k = akey(c["serial"])
            if k and not c["name"].strip():
                self.unnamed.add(k)
            elif k:
                by_serial[c["serial"]] = c["name"]
                self.names.setdefault(k, set()).add(norm(c["name"]))
                self.serial.setdefault(k, c["serial"].strip())
        for v in variants:
            k, alt, name = akey(v["serial"]), v.get("altSerial") or "", by_serial.get(v["serial"])
            ak = akey(alt) if alt else None
            if k and ak and name:
                self.names.setdefault(ak, set()).add(norm(name))
                self.serial.setdefault(ak, alt.strip())
                self.pairs.setdefault(k, set()).add(ak)
                self.pairs.setdefault(ak, set()).add(k)
        self.all_names = set().union(*self.names.values())
        self.prefixes = {k[0] for k in self.names}

    def base_name(self, name: str) -> str:
        """Quita sufijos de versión ('(Full Art)', ' - PR-157'), salvo que el nombre ya exista así ('Ifrit (XVI)')."""
        s = name.strip()
        while norm(s) not in self.all_names:
            t = CODE_SUFFIX.sub("", s).strip()
            if t == s:
                t = PAREN_SUFFIX.sub("", s).strip()
            if t == s:
                t = EX_LB_SUFFIX.sub("", s).strip()
            if t == s or not t:
                break
            s = t
        return s

    def same(self, base: str, k) -> bool:
        return norm(base) in self.names.get(k, ())

    def loosely_same(self, name: str, k) -> bool:
        """Para no reportar diferencias menores ('l'Cie', 'The Crystal Exarch')."""
        a = {norm(name), norm(self.base_name(name)), norm(re.sub(r"\([^()]*\)", "", name))} - {""}
        for b in self.names.get(k, ()):
            for x in a:
                if x == b or (len(b) >= 4 and (x.startswith(b) or b.startswith(x))) \
                        or difflib.SequenceMatcher(None, x, b).ratio() >= 0.85:
                    return True
        return False


# ---------------------------------------------------------------- auditoría

def audit(catalog: dict, ref: Reference) -> tuple[dict, list[dict]]:
    """Devuelve (correcciones {productId: código}, hallazgos). Las correcciones no traen el tope MAX_AUTO."""
    # Código -> nombres base de los productos de TCGplayer que lo usan (para confirmar un código "ocupado")
    tcg_names: dict[tuple, set[str]] = {}
    products = []
    for p in catalog["products"]:
        num = next((e["value"] for e in p.get("extendedData", []) if e["name"] == "Number"), None)
        if not num:
            continue
        products.append((p, num))
        for part in num.split("/"):
            k = akey(part)
            if k:
                tcg_names.setdefault(k, set()).add(norm(ref.base_name(p["name"])))

    auto, rows, missing_sets = {}, [], {}
    for p, num in products:
        pid, name = p["productId"], p["name"]
        base = ref.base_name(name)

        def report(reason, suggested=""):
            rows.append({"pid": pid, "name": name, "code": num, "suggested": suggested, "reason": reason, "auto": False})

        parts = [x.strip() for x in num.split("/") if x.strip()]
        keys = [akey(x) for x in parts]

        # 1) El nombre del producto trae el código ('Cloud - 8-006L (Noir Promo)')
        ncodes = list(dict.fromkeys(k for k in (akey(m) for m in CODE_IN_NAME.findall(name)) if k))
        if ncodes and set(ncodes) - set(keys):
            ok = all(ref.same(base, k) for k in ncodes) and len(ncodes) <= 2 \
                and (len(ncodes) == 1 or ncodes[1] in ref.pairs.get(ncodes[0], ()))
            fixed = "/".join(ref.serial[k] for k in ncodes) if ok else ""
            if ok:
                auto[pid] = fixed
                rows.append({"pid": pid, "name": name, "code": num, "suggested": fixed, "auto": True,
                             "reason": "el nombre del producto trae el código, y la referencia lo confirma"})
            else:
                report("el nombre trae otro código, pero la referencia no lo confirma")
            continue

        new, reasons, doubts = list(parts), [], []
        for i, (part, k) in enumerate(zip(parts, keys)):
            # 2) Formato que la app no lee ('PR165', 'P-059', '007H' sin set)
            if code_key(part) is None or code_key(part) != k:
                if k is None:
                    m = re.match(r"^0*(\d+)([A-Z]?)$", part.upper())
                    cands = {c for o in keys if o for c in ref.pairs.get(o, ())
                             if m and c[1] == int(m[1]) and ref.serial[c].upper().endswith(m[2])}
                    if len(cands) == 1 and ref.same(base, c := cands.pop()):
                        new[i], k = ref.serial[c], c
                        reasons.append(f"'{part}' no trae set; su par en la referencia es {ref.serial[c]}")
                    else:
                        doubts.append((f"formato no reconocido: '{part}'", ""))
                        continue
                elif ref.same(base, k):
                    new[i] = ref.serial[k]
                    reasons.append(f"formato '{part}' -> {ref.serial[k]}")
                else:
                    doubts.append((f"formato raro: '{part}'", ""))
                    continue

            # 3) Código de otra carta, con la correcta al lado ('PR-156' -> 'PR-158')
            if k in ref.unnamed:
                continue
            if k not in ref.names:
                if k[0] not in ref.prefixes:
                    missing_sets.setdefault(k[0], set()).add(pid)
                else:
                    doubts.append((f"{part} no existe en la referencia", _nearby(ref, base, k)))
                continue
            if ref.same(base, k) or ref.loosely_same(name, k):
                continue
            cands = _candidates(ref, base, k)
            other = tcg_names.get(k, set()) & ref.names[k]  # TCGplayer tiene otro producto con el nombre correcto
            if len(cands) == 1 and other:
                c = cands[0]
                new[i] = ref.serial[c]
                reasons.append(f"{part} es otra carta en la referencia; {ref.serial[c]} es '{base}'")
            else:
                why = "" if other else "; ningún otro producto de TCGplayer usa ese código con el nombre de la referencia"
                doubts.append((f"{part} es otra carta en la referencia{why}", ", ".join(ref.serial[c] for c in cands)))

        fixed = "/".join(new)
        if reasons and fixed != num and not doubts:
            auto[pid] = fixed
            rows.append({"pid": pid, "name": name, "code": num, "suggested": fixed, "reason": "; ".join(reasons), "auto": True})
        elif reasons:
            doubts.insert(0, ("; ".join(reasons), fixed))
        for reason, sug in doubts:
            report(reason, sug)

    for prefix, pids in sorted(missing_sets.items()):
        rows.append({"pid": None, "name": f"{len(pids)} productos", "code": f"{prefix}-…", "suggested": "", "auto": False,
                     "reason": f"el set {prefix} todavía no está en la referencia"})
    return auto, rows


def _candidates(ref: Reference, base: str, k) -> list:
    return sorted((c for c in ref.names if c[0] == k[0] and c != k and abs(c[1] - k[1]) <= WINDOW and ref.same(base, c)),
                  key=lambda c: abs(c[1] - k[1]))


def _nearby(ref: Reference, base: str, k) -> str:
    return ", ".join(ref.serial[c] for c in _candidates(ref, base, k))


def auditar(catalog: dict, cache_dir: Path | None = None) -> tuple[dict, list[dict], dict]:
    """Descarga la referencia y audita. Devuelve (correcciones a aplicar, hallazgos, info). Puede lanzar excepciones."""
    cards, variants = fetch_reference(cache_dir)
    auto, rows = audit(catalog, Reference(cards, variants))
    info = {"cards": len(cards), "variants": len(variants), "proposed": len(auto)}
    if len(auto) > MAX_AUTO:
        info["blocked"] = f"propuso {len(auto)} correcciones (máximo {MAX_AUTO}): no se aplicó ninguna"
        for r in rows:
            r["auto"] = False
        auto = {}
    return auto, rows, info


# ---------------------------------------------------------------- reporte

def write_report(path: Path, info: dict, applied: dict, rows: list[dict], manual: dict) -> None:
    def cell(s) -> str:
        return str(s).replace("|", "\\|").replace("\n", " ")

    out = [f"# Auditoría de códigos FFTCG ({date.today().isoformat()})", "",
           "Compara el número de colección que publica TCGplayer con Materia Hunter (lista código -> nombre).", ""]
    if info.get("error"):
        out += [f"**La auditoría no se pudo hacer:** {cell(info['error'])}",
                "", "El índice se generó solo con las correcciones manuales.", ""]
    else:
        out += [f"- Referencia: {info['cards']} cartas, {info['variants']} variantes.",
                f"- Correcciones automáticas aplicadas: {len(applied)}.",
                f"- Correcciones manuales (`CORRECTIONS` en valorizar.py): {len(manual)}; tienen prioridad.", ""]
        if info.get("blocked"):
            out += [f"**Atención:** {info['blocked']}. Revisa si la referencia cambió.", ""]

    autos = [r for r in rows if r["auto"]]
    if autos:
        out += ["## Aplicadas solas", "", "| productId | Nombre TCGplayer | Código TCGplayer | Queda | Motivo |", "|---|---|---|---|---|"]
        for r in autos:
            queda = applied.get(r["pid"]) or f"{manual.get(r['pid'], '')} (manual)"
            out.append(f"| {r['pid']} | {cell(r['name'])} | {cell(r['code'])} | {cell(queda)} | {cell(r['reason'])} |")
        out.append("")

    review = [r for r in rows if not r["auto"]]
    if review or info.get("blocked"):
        out += ["## Para revisar a mano", "", "| productId | Nombre TCGplayer | Código TCGplayer | Sugerido | Motivo |", "|---|---|---|---|---|"]
        for r in review:
            reason = r["reason"] + (f" (ya corregido a mano: {manual[r['pid']]})" if r["pid"] in manual else "")
            out.append(f"| {r['pid'] or ''} | {cell(r['name'])} | {cell(r['code'])} | {cell(r['suggested'])} | {cell(reason)} |")
        out.append("")
    path.write_text("\n".join(out), encoding="utf-8")
