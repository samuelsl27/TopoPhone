"""Tests de la comprobación de ficheros prohibidos en el repositorio (S001/RF-29).

Las cabeceras de clave privada se construyen en tiempo de ejecución para que este fichero
no sea, a su vez, un fichero prohibido.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from repo import forbidden_files

PRIVATE_KEY_HEADER = "-----BEGIN " + "PRIVATE KEY-----"
RSA_KEY_HEADER = "-----BEGIN RSA " + "PRIVATE KEY-----"


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    git(tmp_path, "init", "-q")
    (tmp_path / "README.md").write_text("# Repo\n", encoding="utf-8")
    (tmp_path / "app").mkdir()
    (tmp_path / "app" / "Main.kt").write_text("fun main() = Unit\n", encoding="utf-8")
    git(tmp_path, "add", "-A")
    return tmp_path


def add(root: Path, relative: str, content: str | bytes = "x") -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, bytes):
        path.write_bytes(content)
    else:
        path.write_text(content, encoding="utf-8")
    git(root, "add", "-f", relative)


def test_s001_rf29_un_repositorio_limpio_pasa(repo: Path) -> None:
    """S001/RF-29: sin ficheros prohibidos el resultado es 0."""
    assert forbidden_files.main(["--root", str(repo)]) == 0


@pytest.mark.parametrize(
    "relative",
    [
        "docs/referencias/emlid/manual.pdf",
        "docs/NORMA.PDF",
        "release/topophone.jks",
        "release/upload.keystore",
        "certs/cliente.p12",
        "certs/cliente.pfx",
        "certs/servidor.pem",
        "certs/servidor.key",
    ],
)
def test_s001_rf29_extensiones_prohibidas_se_detectan_y_se_nombran(repo: Path, relative: str, capsys) -> None:
    """S001/RF-29: PDF, almacenes de claves y claves privadas por extensión."""
    add(repo, relative)

    assert forbidden_files.main(["--root", str(repo)]) == 1
    assert relative in capsys.readouterr().out


@pytest.mark.parametrize("relative", ["keystore.properties", "app/keystore.properties", "local.properties", "app/google-services.json", ".env"])
def test_s001_rf29_ficheros_de_claves_o_contrasenas_por_nombre(repo: Path, relative: str, capsys) -> None:
    """S001/RF-29: ficheros de contraseñas y configuración local por nombre."""
    add(repo, relative, "storePassword=secreto\n")

    assert forbidden_files.main(["--root", str(repo)]) == 1
    assert relative in capsys.readouterr().out


@pytest.mark.parametrize("header", [PRIVATE_KEY_HEADER, RSA_KEY_HEADER])
def test_s001_rf29_una_clave_privada_dentro_de_un_fichero_de_texto_se_detecta(repo: Path, header: str, capsys) -> None:
    """S001/RF-29: el contenido con una cabecera de clave privada se detecta aunque la extensión sea inocente."""
    add(repo, "config/notas.txt", f"apuntes\n{header}\nMIIEvQ...\n")

    assert forbidden_files.main(["--root", str(repo)]) == 1
    assert "config/notas.txt" in capsys.readouterr().out


def test_s001_rf29_los_ficheros_no_versionados_no_cuentan(repo: Path) -> None:
    """S001/RF-29: solo se revisa lo que entra en el repositorio (git ls-files)."""
    (repo / "manual.pdf").write_bytes(b"%PDF-1.4")
    (repo / "local.properties").write_text("sdk.dir=C:/sdk\n", encoding="utf-8")

    assert forbidden_files.main(["--root", str(repo)]) == 0


def test_s001_rf29_los_binarios_sin_clave_no_dan_falsos_positivos(repo: Path) -> None:
    """S001/RF-29: un PNG u otro binario no se marca por su contenido."""
    add(repo, "app/src/main/res/drawable/icono.png", bytes(range(256)) * 4)

    assert forbidden_files.main(["--root", str(repo)]) == 0


def test_s001_rf29_el_informe_indica_el_motivo_de_cada_fichero(repo: Path, capsys) -> None:
    """S001/RF-29: cada fichero aparece con su motivo."""
    add(repo, "a.pdf")
    add(repo, "keystore.properties")

    forbidden_files.main(["--root", str(repo)])

    out = capsys.readouterr().out
    assert "a.pdf: extensión prohibida (.pdf)" in out
    assert "keystore.properties: fichero de claves o configuración local" in out
