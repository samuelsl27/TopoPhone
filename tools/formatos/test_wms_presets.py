"""El verificador de presets WMS y lo que grabó (S036/RF-13, RF-16): sin red, con documentos grabados y sintéticos."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_wms as reference  # noqa: E402
import wms_presets as w  # noqa: E402

PRESETS = w.load_presets()
BY_KEY = {p["key"]: p for p in PRESETS}


def synthetic(name: str) -> tuple[int, bytes]:
    return 200, (reference.OUT / name).read_bytes()


def preset(layers: list[str]) -> dict:
    return {"key": "x", "address": "https://x.es/wms", "suggestedLayers": layers}


def test_el_catalogo_tiene_claves_y_direcciones_unicas_y_todas_https():
    assert len({p["key"] for p in PRESETS}) == len(PRESETS)
    assert len({p["address"] for p in PRESETS}) == len(PRESETS)
    assert all(p["address"].startswith("https://") and "?" not in p["address"] for p in PRESETS)
    assert all(p["attribution"] and p["license"] and p["suggestedLayers"] for p in PRESETS)
    assert all(p["requestMode"] in ("TILES", "IMAGE") for p in PRESETS)


def test_el_catalogo_trae_las_36_ortofotos_del_icv_y_las_5_cartografias():
    assert sum(1 for p in PRESETS if p["group"] == "ICV ortofotos") >= 35
    assert sorted(p["key"] for p in PRESETS if p["group"] == "ICV cartografía") == [
        "icv-bcv05", "icv-bcv100", "icv-bcv200", "icv-bcv25", "icv-bcv50",
    ]


def test_se_graban_los_presets_que_no_son_ortofotos_del_icv_y_cuatro_vuelos_de_ellas():
    keys = w.recorded_keys(PRESETS)

    assert {"icv-orto-2025-valencia", "icv-orto-2024", "icv-orto-2017", "icv-orto-1945-amsa"} <= set(keys)
    assert "icv-orto-2023" not in keys and "icv-orto-2025-pval-rapida" not in keys
    assert {"catastro-wms", "ign-pnoa-ma", "navarra-idena", "galicia-orto-pnoa-2023"} <= set(keys)
    assert sum(1 for k in keys if k.startswith("icv-orto-")) == 4


def test_la_url_del_get_capabilities_lleva_el_servicio_la_peticion_y_la_version():
    assert w.capabilities_url("https://x.es/wms", "1.3.0") == "https://x.es/wms?SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0"


def test_un_documento_de_capacidades_con_la_capa_sugerida_pasa():
    result = w.verify(preset(["rgb"]), {"1.3.0": synthetic("caps-1.3.0-basico.xml")})

    assert result["problems"] == []
    assert result["declared"] == {"1.3.0": "1.3.0"}
    assert result["addable_layers"] == 3


def test_la_version_que_declara_el_documento_es_la_que_se_anota_aunque_se_pidiera_otra():
    result = w.verify(preset(["capa-a"]), {"1.3.0": synthetic("caps-1.1.1-basico.xml")})

    assert result["declared"] == {"1.3.0": "1.1.1"} and result["problems"] == []


@pytest.mark.parametrize(
    ("document", "expected"),
    [
        ((500, b"error"), "HTTP 500"),
        (synthetic("error-html-200.html"), "NOT_CAPABILITIES"),
        (synthetic("error-excepcion-1.3.0.xml"), "SERVICE_EXCEPTION"),
        (synthetic("error-xml-danado.xml"), "MALFORMED"),
        (synthetic("caps-1.3.0-sin-3857.xml"), "ninguna capa con nombre y Web Mercator"),
    ],
)
def test_lo_que_no_es_un_documento_util_se_anota_con_su_motivo(document, expected):
    result = w.verify(preset(["rgb"]), {"1.3.0": document})

    assert any(expected in problem for problem in result["problems"]), result["problems"]
    assert result["problems"] != []


def test_una_capa_sugerida_que_no_esta_se_anota():
    result = w.verify(preset(["rgb", "no-existe"]), {"1.3.0": synthetic("caps-1.3.0-basico.xml")})

    assert result["problems"] == ["1.3.0: falta la capa sugerida «no-existe» (con nombre y Web Mercator)"]


def test_una_capa_sugerida_sin_web_mercator_no_cuenta():
    result = w.verify(preset(["a-25830"]), {"1.3.0": synthetic("caps-1.3.0-alias.xml")})

    assert result["problems"] == ["1.3.0: falta la capa sugerida «a-25830» (con nombre y Web Mercator)"]


def test_una_capa_sugerida_con_un_alias_de_web_mercator_vale():
    assert w.verify(preset(["a-3785", "a-102113"]), {"1.3.0": synthetic("caps-1.3.0-alias.xml")})["problems"] == []


def test_si_una_version_falla_y_otra_pasa_se_anota_la_que_falla_pero_hay_documento():
    result = w.verify(preset(["capa-a"]), {"1.3.0": (404, b""), "1.1.1": synthetic("caps-1.1.1-basico.xml")})

    assert result["problems"] == ["1.3.0: HTTP 404"]
    assert result["declared"] == {"1.1.1": "1.1.1"}


def test_sin_ninguna_version_valida_se_dice():
    result = w.verify(preset(["a"]), {"1.3.0": (0, b"sin red"), "1.1.1": (0, b"sin red")})

    assert "ninguna versión devolvió un documento de capacidades" in result["problems"]


def test_se_graban_los_bytes_tal_cual_en_las_dos_versiones(tmp_path):
    docs = {"1.3.0": synthetic("caps-1.3.0-basico.xml"), "1.1.1": synthetic("caps-1.1.1-basico.xml")}

    written = w.record(preset(["rgb"]), docs, tmp_path)

    assert [p.name for p in written] == ["capabilities-1.3.0.xml", "capabilities-1.1.1.xml"]
    assert (tmp_path / "x" / "capabilities-1.3.0.xml").read_bytes() == docs["1.3.0"][1]
    assert (tmp_path / "x" / "capabilities-1.1.1.xml").read_bytes() == docs["1.1.1"][1]


def test_un_documento_que_no_pasa_no_se_graba(tmp_path):
    written = w.record(preset(["rgb"]), {"1.3.0": synthetic("error-html-200.html"), "1.1.1": (500, b"x")}, tmp_path)

    assert written == [] and not (tmp_path / "x").exists()


def test_el_segundo_documento_de_mas_de_un_megabyte_no_se_graba(tmp_path, monkeypatch):
    monkeypatch.setattr(w, "MAX_SECOND_VERSION_BYTES", 100)
    docs = {"1.3.0": synthetic("caps-1.3.0-basico.xml"), "1.1.1": synthetic("caps-1.1.1-basico.xml")}

    written = w.record(preset(["rgb"]), docs, tmp_path)

    assert [p.name for p in written] == ["capabilities-1.3.0.xml"]


def test_los_documentos_grabados_son_de_presets_del_catalogo_y_todos_pasan_la_verificacion():
    folders = sorted(p for p in w.RECORDED.iterdir() if p.is_dir())

    assert {f.name for f in folders} == set(w.recorded_keys(PRESETS))
    for folder in folders:
        files = {f.name[len("capabilities-"):-len(".xml")]: (200, f.read_bytes()) for f in folder.glob("capabilities-*.xml")}
        assert files, folder.name
        result = w.verify(BY_KEY[folder.name], files)
        assert result["problems"] == [], (folder.name, result["problems"])


def test_el_script_sin_red_sale_con_0_si_todo_pasa_y_con_1_si_algo_falla(monkeypatch, capsys):
    one = {"1.3.0": synthetic("caps-1.3.0-basico.xml"), "1.1.1": synthetic("caps-1.1.1-basico.xml")}
    monkeypatch.setattr(w, "load_presets", lambda: [{**preset(["rgb"]), "key": "ok", "group": "G"}])
    monkeypatch.setattr(w, "fetch", lambda url: one["1.3.0" if "VERSION=1.3.0" in url else "1.1.1"])

    assert w.main(["--delay", "0"]) == 1  # el 1.1.1 de ejemplo no tiene la capa `rgb`
    assert "FALLA ok" in capsys.readouterr().out
    monkeypatch.setattr(w, "load_presets", lambda: [{**preset(["capa-a"]), "key": "ok", "group": "G"}])
    monkeypatch.setattr(w, "fetch", lambda url: one["1.1.1"])

    assert w.main(["--delay", "0"]) == 0
    assert "OK ok" in capsys.readouterr().out


def test_el_script_con_record_graba_donde_se_le_dice(monkeypatch, tmp_path):
    monkeypatch.setattr(w, "load_presets", lambda: [{**preset(["capa-a"]), "key": "ok", "group": "G"}])
    monkeypatch.setattr(w, "fetch", lambda url: synthetic("caps-1.1.1-basico.xml"))
    monkeypatch.setattr(w, "RECORDED", tmp_path / "presets")
    monkeypatch.setattr(w, "ROOT", tmp_path)

    assert w.main(["--delay", "0", "--record"]) == 0
    assert sorted(p.name for p in (tmp_path / "presets" / "ok").iterdir()) == ["capabilities-1.1.1.xml", "capabilities-1.3.0.xml"]
    assert (tmp_path / "esperado-presets.txt").exists()


def test_only_limita_los_presets_que_se_piden(monkeypatch, capsys):
    monkeypatch.setattr(w, "load_presets", lambda: [{**preset(["capa-a"]), "key": k, "group": "G"} for k in ("a", "b", "c")])
    monkeypatch.setattr(w, "fetch", lambda url: synthetic("caps-1.1.1-basico.xml"))

    w.main(["--delay", "0", "--only", "a,c"])

    out = capsys.readouterr().out
    assert "OK a" in out and "OK c" in out and "OK b" not in out


def test_lo_esperado_de_los_documentos_grabados_esta_al_dia_y_tiene_dos_lineas_por_documento():
    expected = w.expected_path().read_bytes()

    assert expected == w.expected_presets()
    lines = expected.decode("utf-8").splitlines()
    docs = list(w.RECORDED.glob("*/capabilities-*.xml"))
    assert len(lines) == 2 * len(docs)
    assert all(line.startswith(("service|", "layers|")) for line in lines)


def test_el_resumen_de_capas_cambia_si_cambia_una_capa():
    one = w.digest_lines("a", reference.generate_all()["caps-1.3.0-basico.xml"])
    other = w.digest_lines("a", reference.generate_all()["caps-1.3.0-sin-nombre.xml"])

    assert one[1].split("|")[3] != other[1].split("|")[3]
    assert one[1].split("|")[2] == "3" and other[1].split("|")[2] == "4"
