"""Rejillas del CDN de PROJ que ofrece la biblioteca de SC de S034: tamaño, SHA-256, fuente, licencia y cobertura (ADR-4).

`files.geojson` del CDN da el nombre, el tamaño, el SHA-256 y la fuente de cada fichero, pero no la licencia ni la cobertura de los geoides:
- la licencia sale del `<fuente>_README.txt` de cada fuente (cada bloque `###` declara su `*License*` y lista sus ficheros);
- la cobertura de un geoide sale de la cabecera de su GeoTIFF (se piden solo los primeros KB con `Range`).

Para que regenerar el catálogo dé siempre lo mismo, el resultado se guarda en `datos/rejillas_cdn.json` (los ficheros del CDN son inmutables:
su SHA-256 no cambia); solo `--actualizar` vuelve a consultar el CDN. Las licencias que no se reconocen quedan como «no clara»: la app las ofrece
igualmente con un aviso y el enlace a la fuente (S034/RF-20).
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import urllib.request
from pathlib import Path

CDN = "https://cdn.proj.org/"
INSTANTANEA = Path(__file__).resolve().parent / "datos" / "rejillas_cdn.json"
TIPO_GEOIDE = "VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL"
TIPO_HORIZONTAL = "HORIZONTAL_OFFSET"
INCLUIDAS_EN_LA_APP = {"es_ign_egm08-rednap.tif", "es_ign_egm08-rednap-canarias.tif", "es_ign_SPED2ETV2.tif"}  # ADR-4, S034/RF-17
BYTES_CABECERA = 262144

# (patrón en el texto de la licencia, id, nombre, ¿abierta?, enlace)
LICENCIAS = [
    (r"by-sa|share-?alike|by sa", "CC-BY-SA-4.0", "Creative Commons Atribución-CompartirIgual 4.0", True, "https://creativecommons.org/licenses/by-sa/4.0/"),
    (r"cc0|publicdomain/zero", "CC0-1.0", "Creative Commons CC0 1.0 (dominio público)", True, "https://creativecommons.org/publicdomain/zero/1.0/"),
    (r"creative commons attribution|cc[ -]by", "CC-BY-4.0", "Creative Commons Atribución 4.0", True, "https://creativecommons.org/licenses/by/4.0/"),
    (r"public domain", "PD", "Dominio público", True, ""),
    (r"open government licence", "OGL-Canada", "Licencia de gobierno abierto de Canadá", True, "http://open.canada.ca/en/open-government-licence-canada"),
    (r"licen[cs]e ouverte|open licen[cs]e", "Etalab-2.0", "Licence Ouverte 2.0 (Etalab)", True, "https://www.etalab.gouv.fr/licence-ouverte-open-licence/"),
    (r"data licence germany|dl-de/by", "DL-DE-BY-2.0", "Licencia de datos de Alemania, atribución 2.0", True, "https://www.govdata.de/dl-de/by-2-0"),
    (r"bsd", "BSD-2-Clause", "BSD de 2 cláusulas", True, "https://opensource.org/licenses/bsd-license.php"),
    (r"free redistribution", "libre", "Redistribución libre permitida por la fuente", True, ""),
]
NO_CLARA = ("no-clara", "Licencia no clara: consulta la fuente", False, "")  # fmt: skip


def _leer(url: str, rango: tuple[int, int] | None = None) -> bytes:
    peticion = urllib.request.Request(url, headers={"Range": f"bytes={rango[0]}-{rango[1]}"} if rango else {})
    with urllib.request.urlopen(peticion, timeout=60) as respuesta:
        return respuesta.read()


def licencia_de(texto: str) -> tuple[str, str, bool, str]:
    """(id, nombre, ¿abierta?, enlace) de la licencia que declara un README; «no clara» si no se reconoce."""
    minuscula = texto.lower()
    for patron, ident, nombre, abierta, enlace in LICENCIAS:
        if re.search(patron, minuscula):
            enlace_readme = re.search(r"\]\((https?://[^)\s]+)\)", texto)
            return ident, nombre, abierta, enlace or (enlace_readme.group(1) if enlace_readme else "")
    return NO_CLARA


def licencias_del_readme(texto: str) -> dict[str, str]:
    """Nombre de fichero → texto de la línea `*License*` de su bloque (`###`) en un README del CDN."""
    resultado: dict[str, str] = {}
    actual = ""
    for linea in texto.splitlines():
        if linea.startswith("###") and not linea.startswith("####"):
            actual = ""
        encontrado = re.match(r"\s*[-*]?\s*\*License\*:\s*(.*)$", linea)
        if encontrado:
            actual = encontrado.group(1)
            continue
        fichero = re.match(r"\s*[-*]\s+(\S+\.tif)\b", linea)
        if fichero and actual:
            resultado[fichero.group(1)] = actual
    return resultado


def licencia_unica_del_readme(texto: str) -> str:
    """El texto de la licencia si todas las líneas `*License*` del README declaran la misma; vacío si hay varias o ninguna."""
    lineas = {m.group(1).strip() for m in re.finditer(r"\*License\*:\s*(.*)", texto)}
    if not lineas:  # README sin líneas `*License*` (el de Nueva Zelanda cita la licencia en el texto)
        return texto if licencia_de(texto)[0] != "no-clara" else ""
    clases = {licencia_de(linea)[0] for linea in lineas}
    return next(iter(lineas)) if len(clases) == 1 and "no-clara" not in clases else ""


# --- Cabecera GeoTIFF: cobertura de un geoide ---------------------------------------------------------------------------------------

TIPOS = {3: ("H", 2), 4: ("I", 4), 12: ("d", 8), 16: ("Q", 8), 5: ("II", 8)}


class _Cabecera:
    """Lector mínimo de las etiquetas de la primera imagen de un TIFF o BigTIFF, con `Range` bajo demanda."""

    def __init__(self, url: str) -> None:
        self.url = url
        self.datos = _leer(url, (0, BYTES_CABECERA - 1))
        self.fin = "<" if self.datos[:2] == b"II" else ">"
        magia = struct.unpack(self.fin + "H", self.datos[2:4])[0]
        self.grande = magia == 43
        self.desplazamiento = struct.unpack(self.fin + ("Q" if self.grande else "I"), self.datos[8:16] if self.grande else self.datos[4:8])[0]

    def bytes_(self, inicio: int, longitud: int) -> bytes:
        if inicio + longitud > len(self.datos):
            return _leer(self.url, (inicio, inicio + longitud - 1))
        return self.datos[inicio : inicio + longitud]

    def etiquetas(self) -> dict[int, list[float]]:
        f = self.fin
        entrada = 20 if self.grande else 12
        cuenta_tam = 8 if self.grande else 2
        cabecera = self.bytes_(self.desplazamiento, cuenta_tam)
        cuenta = struct.unpack(f + ("Q" if self.grande else "H"), cabecera)[0]
        bloque = self.bytes_(self.desplazamiento + cuenta_tam, cuenta * entrada)
        salida: dict[int, list[float]] = {}
        for k in range(cuenta):
            e = bloque[k * entrada : (k + 1) * entrada]
            if self.grande:
                etiqueta, tipo, n = struct.unpack(f + "HHQ", e[:12])
                valor = e[12:20]
            else:
                etiqueta, tipo, n = struct.unpack(f + "HHI", e[:8])
                valor = e[8:12]
            if tipo not in TIPOS or etiqueta not in (256, 257, 33550, 33922, 34264):
                continue
            formato, tam = TIPOS[tipo]
            if tam * n > len(valor):
                desplazamiento = struct.unpack(f + ("Q" if self.grande else "I"), valor)[0]
                valor = self.bytes_(desplazamiento, tam * n)
            salida[etiqueta] = list(struct.unpack(f + formato * n, valor[: tam * n]))
        return salida


def cobertura(nombre: str) -> tuple[float, float, float, float] | None:
    """(sur, oeste, norte, este) en grados del GeoTIFF por su cabecera, o `None` si no es geográfico o no se entiende."""
    try:
        etiquetas = _Cabecera(CDN + nombre).etiquetas()
        ancho, alto = etiquetas[256][0], etiquetas[257][0]
        if 34264 in etiquetas:
            g = etiquetas[34264]
            x0, dx, y0, dy = g[3], g[0], g[7], g[5]
        else:
            x0, y0 = etiquetas[33922][3], etiquetas[33922][4]
            dx, dy = etiquetas[33550][0], -etiquetas[33550][1]
    except (KeyError, IndexError, struct.error, OSError):
        return None
    oeste, este = x0, x0 + dx * (ancho - 1)
    norte, sur = y0, y0 + dy * (alto - 1)
    return round(min(sur, norte), 6), round(min(oeste, este), 6), round(max(sur, norte), 6), round(max(oeste, este), 6)


# --- Instantánea -------------------------------------------------------------------------------------------------------------------


def construir_instantanea() -> dict:
    """Consulta el CDN: ficheros de desplazamiento horizontal y de geoides con su tamaño, SHA-256, fuente, licencia y cobertura (geoides)."""
    ficheros = json.loads(_leer(CDN + "files.geojson"))["features"]
    propiedades = [f["properties"] for f in ficheros if f["properties"].get("type") in (TIPO_HORIZONTAL, TIPO_GEOIDE)]
    readmes: dict[str, dict[str, str]] = {}
    unicas: dict[str, str] = {}
    filas = []
    for p in sorted(propiedades, key=lambda q: q["name"]):
        fuente = p["source_id"]
        if fuente not in readmes:
            try:
                texto = _leer(f"{CDN}{fuente}_README.txt").decode("utf-8", "replace")
                readmes[fuente], unicas[fuente] = licencias_del_readme(texto), licencia_unica_del_readme(texto)
            except OSError:
                readmes[fuente], unicas[fuente] = {}, ""
        texto_licencia = readmes[fuente].get(p["name"], "") or unicas[fuente]
        ident, nombre_licencia, abierta, enlace = licencia_de(texto_licencia) if texto_licencia else NO_CLARA
        fila = {
            "name": p["name"],
            "type": "V" if p["type"] == TIPO_GEOIDE else "H",
            "size": p["file_size"],
            "sha256": p["sha256sum"],
            "source": p["source"],
            "sourceUrl": p["source_url"],
            "license": {"id": ident, "name": nombre_licencia, "open": abierta, "url": enlace},
            "targetCrs": p.get("target_crs_code", ""),
            "targetName": p.get("target_crs_name", ""),
        }
        if p["type"] == TIPO_GEOIDE:
            fila["area"] = cobertura(p["name"])
        filas.append(fila)
    return {"cdn": CDN, "grids": filas}


def leer_instantanea() -> dict:
    return json.loads(INSTANTANEA.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--actualizar", action="store_true", help="volver a consultar el CDN y reescribir datos/rejillas_cdn.json")
    args = parser.parse_args(argv)
    if args.actualizar or not INSTANTANEA.exists():
        INSTANTANEA.parent.mkdir(parents=True, exist_ok=True)
        instantanea = construir_instantanea()
        INSTANTANEA.write_text(json.dumps(instantanea, indent=1, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    datos = leer_instantanea()
    print(f"{len(datos['grids'])} rejillas; sin licencia clara: {sum(1 for g in datos['grids'] if g['license']['id'] == 'no-clara')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
