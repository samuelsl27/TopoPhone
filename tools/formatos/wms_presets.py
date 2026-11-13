"""Verifica en vivo los servicios WMS de `presets.json` y graba sus GetCapabilities para los tests (S036/RF-13, RF-16).

Para cada preset pide el GetCapabilities en 1.3.0 y en 1.1.1, lo lee con el lector de referencia de `generar_wms.py` y comprueba que:
- responde un documento de capacidades (no una excepción ni una página de error);
- tiene alguna capa con nombre y con un código de Web Mercator (EPSG:3857 o un alias), que es lo que la app puede pedir;
- tiene todas las capas sugeridas del catálogo, con nombre y con Web Mercator.

No se ejecuta en CI: la red no entra en los tests. `--record` graba el documento tal como llegó (los bytes, sin tocar) en
`fixtures/formatos/wms/presets/<clave>/capabilities-<versión>.xml` (y `esperado-presets.txt` con lo que debe leer el lector de Kotlin de cada uno,
calculado con el lector de referencia) para los presets que los tests leen: todos los que no son de una ortofoto del
ICV, y cuatro vuelos del ICV (2025, 2024, 2017 y 1945). Un documento de más de 1 MB solo se graba en una versión (la otra pesa casi lo mismo).

Uso:
    uv run --project tools tools/formatos/wms_presets.py                 verifica todos los presets en vivo y lista el resultado
    uv run --project tools tools/formatos/wms_presets.py --only ign-pnoa-ma,icv-orto-2024
    uv run --project tools tools/formatos/wms_presets.py --record        además graba los documentos de los presets de los tests
Sale con 1 si algún preset no pasa la verificación.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_wms as reference  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
PRESETS = ROOT / "core" / "formats" / "src" / "main" / "resources" / "es" / "topophone" / "core" / "formats" / "wms" / "presets.json"
RECORDED = ROOT / "fixtures" / "formatos" / "wms" / "presets"
USER_AGENT = "TopoPhone-presets-check"
TIMEOUT_S = 25
VERSIONS = ("1.3.0", "1.1.1")
MAX_SECOND_VERSION_BYTES = 1_000_000
ICV_RECORDED = {"icv-orto-2025-valencia", "icv-orto-2024", "icv-orto-2017", "icv-orto-1945-amsa"}


def load_presets() -> list[dict]:
    return json.loads(PRESETS.read_text(encoding="utf-8"))


def recorded_keys(presets: list[dict]) -> list[str]:
    """Los presets cuyos documentos se graban: los que no son una ortofoto del ICV y cuatro vuelos de ellas."""
    return [p["key"] for p in presets if p["group"] != "ICV ortofotos" or p["key"] in ICV_RECORDED]


def capabilities_url(address: str, version: str) -> str:
    return f"{address}?SERVICE=WMS&REQUEST=GetCapabilities&VERSION={version}"


def fetch(url: str) -> tuple[int, bytes]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def verify(preset: dict, documents: dict[str, tuple[int, bytes]]) -> dict:
    """El resultado de comprobar un preset con los documentos que respondió en cada versión: `problems` vacío si pasa."""
    problems: list[str] = []
    versions: dict[str, str] = {}
    layers_found = 0
    for version, (status, data) in documents.items():
        if status != 200:
            problems.append(f"{version}: HTTP {status}")
            continue
        read = reference.read_capabilities(data)
        if read["state"] != "ok":
            problems.append(f"{version}: {read['state']}")
            continue
        versions[version] = read["version"]
        addable = {layer["name"]: layer for layer in read["layers"] if layer["name"] and layer["code"]}
        layers_found = max(layers_found, len(addable))
        if not addable:
            problems.append(f"{version}: ninguna capa con nombre y Web Mercator")
        for wanted in preset["suggestedLayers"]:
            if wanted not in addable:
                problems.append(f"{version}: falta la capa sugerida «{wanted}» (con nombre y Web Mercator)")
    if not versions:
        problems.append("ninguna versión devolvió un documento de capacidades")
    return {"key": preset["key"], "problems": problems, "declared": versions, "addable_layers": layers_found}


def record(preset: dict, documents: dict[str, tuple[int, bytes]], folder: Path | None = None) -> list[Path]:
    """Graba los documentos que pasaron: ambas versiones, o solo la primera si el documento pasa de 1 MB."""
    written = []
    target = (folder or RECORDED) / preset["key"]
    for index, (version, (status, data)) in enumerate(documents.items()):
        if status != 200 or reference.read_capabilities(data)["state"] != "ok":
            continue
        if index > 0 and len(data) > MAX_SECOND_VERSION_BYTES:
            continue
        target.mkdir(parents=True, exist_ok=True)
        path = target / f"capabilities-{version}.xml"
        path.write_bytes(data)
        written.append(path)
    return written


def expected_path() -> Path:
    """`esperado-presets.txt`, junto a la carpeta de los documentos grabados (se calcula al llamar, para poder cambiar `RECORDED` en las pruebas)."""
    return RECORDED.parent / "esperado-presets.txt"


def digest_lines(name: str, data: bytes) -> list[str]:
    """Lo que debe leer la app de un documento grabado: su línea de servicio y el recuento y el SHA-256 de sus líneas de capa."""
    lines = reference.expected_lines({name: data})
    layers = [line for line in lines if line.startswith("layer|")]
    sha = hashlib.sha256("\n".join(layers).encode("utf-8")).hexdigest()
    return [next(line for line in lines if line.startswith("service|")), f"layers|{name}|{len(layers)}|{sha}"]


def expected_presets(folder: Path | None = None) -> bytes:
    """`esperado-presets.txt`: una línea de servicio y una de capas por cada documento grabado, en orden de nombre."""
    out: list[str] = []
    for doc in sorted((folder or RECORDED).glob("*/capabilities-*.xml")):
        out += digest_lines(f"{doc.parent.name}/{doc.name}", doc.read_bytes())
    return ("\n".join(out) + "\n").encode("utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", help="claves separadas por comas")
    parser.add_argument("--record", action="store_true", help="graba los documentos de los presets de los tests")
    parser.add_argument("--delay", type=float, default=1.0, help="segundos entre peticiones (por defecto 1)")
    args = parser.parse_args(argv)
    presets = load_presets()
    wanted = set(args.only.split(",")) if args.only else None
    to_record = set(recorded_keys(presets)) if args.record else set()
    failed = 0
    for preset in presets:
        if wanted is not None and preset["key"] not in wanted:
            continue
        documents: dict[str, tuple[int, bytes]] = {}
        for version in VERSIONS:
            started = time.time()
            try:
                documents[version] = fetch(capabilities_url(preset["address"], version))
            except OSError as error:
                documents[version] = (0, str(error).encode())
            print(f"  {preset['key']} {version}: HTTP {documents[version][0]}, {len(documents[version][1])} bytes, {time.time() - started:.1f} s")
            time.sleep(args.delay)
        result = verify(preset, documents)
        status = "OK" if not result["problems"] else "FALLA"
        print(f"{status} {preset['key']}: declara {result['declared']}, {result['addable_layers']} capas que se pueden añadir")
        for problem in result["problems"]:
            print(f"    - {problem}")
        if result["problems"]:
            failed += 1
        if preset["key"] in to_record:
            for path in record(preset, documents):
                print(f"    grabado {path.relative_to(ROOT)}")
    if args.record:
        path = expected_path()
        path.write_bytes(expected_presets())
        print(f"    grabado {path.relative_to(ROOT)}")
    print(f"{failed} presets con problemas")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
