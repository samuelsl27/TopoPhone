"""El ZIP de fotos y su índice de referencia están al día, siguen las reglas de la spec y se leen con `zipfile` y `csv` (S056/RF-4 a RF-7)."""

from __future__ import annotations

import csv
import io
import re
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_fotos_zip as g  # noqa: E402


@pytest.fixture(scope="module")
def points():
    return g.parse_input((g.OUT / "entrada-fotos.txt").read_text(encoding="utf-8"))


@pytest.fixture
def zip_path(tmp_path, points):
    path = tmp_path / "fotos.zip"
    g.build_zip(points, path)
    return path


def read_index(data: bytes) -> list[list[str]]:
    return list(csv.reader(io.StringIO(data.decode("utf-8"), newline="")))


def test_las_referencias_guardadas_son_las_que_genera_el_script():
    for name, data in g.generate_all().items():
        assert (g.OUT / name).read_bytes() == data, name


def test_la_entrada_se_lee_y_se_vuelve_a_escribir_igual(points):
    assert g.input_text(points).encode("utf-8") == (g.OUT / "entrada-fotos.txt").read_bytes()


def test_el_ejemplo_cubre_los_casos_de_la_spec(points):
    assert max(len(p.photos) for p in points) == 10  # diez fotos en un punto
    assert any(p.photos == [] for p in points)  # un punto sin fotos
    assert not all(photo.exif for p in points for photo in p.photos)  # una foto sin metadatos
    assert {p.name for p in points} >= {"Árbol ñ/9", "Arbol n 9", 'Esquina "norte", lado 2'}


def test_el_nombre_del_punto_se_sanea_sin_acentos_y_con_guion_bajo_en_lo_demas():
    assert g.sanitize("Árbol ñ/9") == "Arbol_n_9"
    assert g.sanitize("Esquina \"norte\", lado 2") == "Esquina__norte___lado_2"
    assert g.sanitize("P-1_b") == "P-1_b"
    assert g.sanitize("   ") == "punto"
    assert g.sanitize("") == "punto"
    assert g.sanitize("  ñandú  ") == "nandu"


def test_los_nombres_no_se_repiten_ni_distinguiendo_mayusculas(points):
    flat = [n for given in g.names(points) for n in given]
    assert len(flat) == sum(len(p.photos) for p in points)
    assert len({n.lower() for n in flat}) == len(flat)


def test_los_nombres_de_los_casos_limite(points):
    given = dict(zip([p.name for p in points], g.names(points)))
    assert given["P1"] == [f"P1_{i}.jpg" for i in range(1, 11)]
    assert given["Árbol ñ/9"] == ["Arbol_n_9_1.jpg"]
    assert given["Arbol n 9"] == ["Arbol_n_9_2_1.jpg", "Arbol_n_9_2_2.jpg"]  # el segundo que satura a lo mismo lleva _2 en el punto
    assert given['Esquina "norte", lado 2'] == ["Esquina__norte___lado_2_1.jpg"]
    assert given["P2"] == []
    assert given["p2"] == ["p2_1.jpg"]  # el punto sin fotos que se llamaba igual no gastó el nombre
    assert given["A"] == ["A_1.jpg", "A_2.jpg"]
    assert given["a"] == ["a_2_1.jpg"]  # sin distinguir mayúsculas


def test_el_indice_no_lleva_bom_usa_crlf_y_las_comillas_justas():
    raw = (g.OUT / g.INDEX_NAME).read_bytes()
    assert not raw.startswith(b"\xef\xbb\xbf")
    assert raw.endswith(b"\r\n")
    assert raw.count(b"\n") == raw.count(b"\r\n")
    assert raw.split(b"\r\n")[0] == b"Photo,Point,Number,Date,Source,Size"
    assert b'"Esquina ""norte"", lado 2"' in raw  # coma y comillas: entre comillas dobles y con las comillas duplicadas
    assert b'"' not in raw.replace(b'"Esquina ""norte"", lado 2"', b"")  # ningún otro campo va entre comillas


def test_el_indice_tiene_una_fila_por_foto_con_sus_datos(points):
    rows = read_index((g.OUT / g.INDEX_NAME).read_bytes())
    assert rows[0] == g.HEADER
    body = rows[1:]
    expected = [
        (name, point.name, str(number), g.utc(photo.taken_ms), photo.origin, str(photo.size))
        for point, given in zip(points, g.names(points))
        for number, (photo, name) in enumerate(zip(point.photos, given), start=1)
    ]
    assert [tuple(r) for r in body] == expected
    for _, _, number, date, source, size in body:
        assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", date)  # ISO 8601 con la Z, al segundo
        assert source in ("camera", "gallery")
        assert int(number) >= 1 and int(size) > 0


def test_la_fecha_se_corta_al_segundo_en_utc():
    assert g.utc(g.BASE_MS) == "2026-11-12T08:15:30Z"  # 123 ms de más
    assert g.utc(0) == "1970-01-01T00:00:00Z"


def test_el_numero_de_cada_foto_es_su_posicion_en_su_punto(points):
    rows = read_index((g.OUT / g.INDEX_NAME).read_bytes())[1:]
    by_point: dict[int, list[int]] = {}
    cursor = 0
    for index, point in enumerate(points):
        count = len(point.photos)
        by_point[index] = [int(r[2]) for r in rows[cursor:cursor + count]]
        cursor += count
        assert by_point[index] == list(range(1, count + 1)), point.name
    assert cursor == len(rows)


def test_el_zip_lleva_las_fotos_en_el_orden_del_indice_y_el_indice_el_ultimo(zip_path, points):
    expected = [n for given in g.names(points) for n in given] + [g.INDEX_NAME]
    with zipfile.ZipFile(zip_path) as z:
        assert z.testzip() is None
        assert [i.filename for i in z.infolist()] == expected
        assert z.read(g.INDEX_NAME) == (g.OUT / g.INDEX_NAME).read_bytes()


def test_cada_foto_sale_del_zip_igual_que_entro_byte_a_byte(zip_path, points):
    with zipfile.ZipFile(zip_path) as z:
        for index, (point, given) in enumerate(zip(points, g.names(points))):
            for number, (photo, name) in enumerate(zip(point.photos, given), start=1):
                data = z.read(name)
                assert data == g.photo_bytes(index, number, photo), name
                assert len(data) == photo.size
                assert data[:2] == b"\xff\xd8" and data[-2:] == b"\xff\xd9"


def test_la_foto_sin_metadatos_no_lleva_el_segmento_exif(points):
    for index, point in enumerate(points):
        for number, photo in enumerate(point.photos, start=1):
            data = g.photo_bytes(index, number, photo)
            assert (b"Exif\x00\x00" in data[:30]) == photo.exif, (point.name, number)


def test_el_indice_del_zip_se_abre_con_csv_y_coincide_con_las_entradas_del_zip(zip_path):
    with zipfile.ZipFile(zip_path) as z:
        rows = read_index(z.read(g.INDEX_NAME))[1:]
        sizes = {i.filename: i.file_size for i in z.infolist()}
    for name, _, _, _, _, size in rows:
        assert sizes[name] == int(size), name
