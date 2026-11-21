"""Vectores de geoides del CDN de PROJ para S034 (RF-14, RF-16): ondulación de tres rejillas pequeñas con `vgridshift` de PROJ.

Uso: uv run --project tools tools/geodesia/geoides_cdn.py
Descarga (si faltan) las rejillas de `fixtures/geodesia/rejillas/`, comprueba su SHA-256 con `datos/rejillas_cdn.json` y escribe
`fixtures/geodesia/geoide_cdn.csv`: rejilla, latitud, longitud y N en metros (vacío fuera de la cobertura). Las rejillas llevan su licencia y
su atribución en `fixtures/geodesia/rejillas/LEEME.md`.
"""

from __future__ import annotations

import hashlib
import random
import sys
import urllib.request
from pathlib import Path

import pyproj
from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rejillas_cdn  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CARPETA = ROOT / "fixtures" / "geodesia" / "rejillas"
SALIDA = ROOT / "fixtures" / "geodesia" / "geoide_cdn.csv"
REJILLAS = ["fr_ign_ggpf02-Bora.tif", "cz_cuzk_CR-2005.tif", "us_noaa_g2012bg0.tif"]
SEMILLA = 20261121


def asegurar(nombre: str, sha256: str) -> Path:
    ruta = CARPETA / nombre
    if not ruta.exists():
        CARPETA.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(rejillas_cdn.CDN + nombre, timeout=60) as respuesta:
            ruta.write_bytes(respuesta.read())
    actual = hashlib.sha256(ruta.read_bytes()).hexdigest()
    if actual != sha256:
        raise SystemExit(f"SHA-256 de {nombre} incorrecto: {actual}")
    return ruta


def generar() -> str:
    por_nombre = {g["name"]: g for g in rejillas_cdn.leer_instantanea()["grids"]}
    rng = random.Random(SEMILLA)
    lineas = [
        "# Ondulación de geoides pequeños del CDN de PROJ (S034). Generado por tools/geodesia/geoides_cdn.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
        "rejilla,latitud,longitud,ondulacion",
    ]
    for nombre in REJILLAS:
        grid = por_nombre[nombre]
        ruta = asegurar(nombre, grid["sha256"])
        sur, oeste, norte, este = grid["area"]
        transformador = Transformer.from_pipeline(f"+proj=pipeline +step +proj=vgridshift +grids={ruta.as_posix()} +multiplier=1")
        # Las esquinas se meten un 0,5 % hacia dentro: el recuadro de la cabecera llega al borde de la celda y los nodos empiezan media celda más adentro.
        margen_lat, margen_lon = 0.005 * (norte - sur), 0.005 * (este - oeste)
        dentro = [
            (sur + margen_lat, oeste + margen_lon),
            (sur + margen_lat, este - margen_lon),
            (norte - margen_lat, oeste + margen_lon),
            (norte - margen_lat, este - margen_lon),
            ((sur + norte) / 2, (oeste + este) / 2),
        ]
        dentro += [(rng.uniform(sur, norte), rng.uniform(oeste, este)) for _ in range(40)]
        fuera = [(sur - 1.0, oeste), (norte + 1.0, este), ((sur + norte) / 2, oeste - 1.0), ((sur + norte) / 2, este + 1.0)]
        for latitud, longitud in dentro + fuera:
            _, _, n = transformador.transform(longitud, latitud, 0.0)
            valor = "" if not abs(n) < 1e6 else repr(float(n))
            lineas.append(",".join([nombre, repr(float(latitud)), repr(float(longitud)), valor]))
    return "\n".join(lineas) + "\n"


def main() -> int:
    SALIDA.write_text(generar(), encoding="utf-8", newline="\n")
    (CARPETA / "LEEME.md").write_text(
        "# Rejillas de geoide de prueba (S034)\n\n"
        "Tres geoides pequeños del CDN de PROJ (https://cdn.proj.org) para contrastar la lectura de geoides descargados con PROJ. No van en el APK.\n\n"
        "- `fr_ign_ggpf02-Bora.tif`: © IGN (Francia), Licence Ouverte 2.0 (Etalab).\n"
        "- `cz_cuzk_CR-2005.tif`: © ČÚZK (República Checa), CC-BY 4.0.\n"
        "- `us_noaa_g2012bg0.tif`: NOAA (EE. UU.), dominio público.\n\n"
        "El SHA-256 de cada una está en `tools/geodesia/datos/rejillas_cdn.json`; `tools/geodesia/geoides_cdn.py` las comprueba al regenerar.\n",
        encoding="utf-8",
        newline="\n",
    )
    print("geoide_cdn.csv escrito")
    return 0


if __name__ == "__main__":
    sys.exit(main())
