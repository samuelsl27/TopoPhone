"""Tests del receptor Wi-Fi de mentira (S021): sirve una sesión de fixtures, recibe correcciones y corta y reanuda."""

from __future__ import annotations

import socket
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import servidor_tcp as s  # noqa: E402


def _leer_linea(conexion: socket.socket, plazo: float = 3.0) -> bytes:
    conexion.settimeout(plazo)
    datos = b""
    while not datos.endswith(b"\n"):
        trozo = conexion.recv(1)
        if not trozo:
            break
        datos += trozo
    return datos


def _esperar(condicion, plazo: float = 5.0) -> bool:
    limite = time.time() + plazo
    while time.time() < limite:
        if condicion():
            return True
        time.sleep(0.02)
    return False


@pytest.fixture
def epocas() -> list[list[bytes]]:
    return s.leer_epocas(s.NMEA_POR_DEFECTO)


@pytest.fixture
def servidores():
    creados: list[s.Servidor] = []
    yield creados
    for servidor in creados:
        servidor.detener()


def _levantar(servidores, epocas, **opciones) -> s.Servidor:
    servidor = s.Servidor(epocas, periodo=0.01, **opciones).iniciar()
    servidores.append(servidor)
    return servidor


def test_s021_la_sesion_de_fixtures_se_agrupa_en_epocas_que_empiezan_por_gga(epocas):
    """S021/Demo: la sesión de fixtures se sirve por épocas completas."""
    assert len(epocas) > 10
    for epoca in epocas[1:]:
        assert epoca[0][3:6] == b"GGA"
        assert all(frase.startswith(b"$") and frase.endswith(b"\r\n") for frase in epoca)
    assert sum(len(e) for e in epocas) > len(epocas)  # además de la GGA trae GSA, GSV, GST…


def test_s021_el_servidor_sirve_la_primera_epoca_por_tcp_y_sigue_en_bucle(servidores, epocas):
    """S021/Demo: un cliente recibe la primera GGA enseguida y las épocas siguientes sin parar."""
    servidor = _levantar(servidores, epocas)

    with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=3) as cliente:
        primera = _leer_linea(cliente)
        assert primera == epocas[0][0]
        gga = 0
        for _ in range(len(epocas[0]) * 3 + 20):
            gga += _leer_linea(cliente)[3:6] == b"GGA"
        assert gga >= 3


def test_s021_el_bucle_vuelve_a_empezar_al_acabar_la_sesion(servidores):
    """S021/Demo: al acabar el fichero vuelve a servir desde el principio."""
    corta = [[b"$GNGGA,1*00\r\n", b"$GNGST,1*00\r\n"], [b"$GNGGA,2*00\r\n"]]
    servidor = _levantar(servidores, corta)

    with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=3) as cliente:
        vistas = [_leer_linea(cliente) for _ in range(7)]

    assert vistas[:3] == [b"$GNGGA,1*00\r\n", b"$GNGST,1*00\r\n", b"$GNGGA,2*00\r\n"]
    assert vistas[3:6] == vistas[:3]


def test_s021_el_puerto_de_correcciones_cuenta_lo_que_recibe(servidores, epocas):
    """S021/Demo: lo que la app escribe en el puerto de correcciones se cuenta, sin tocar la posición."""
    servidor = _levantar(servidores, epocas, puerto_correcciones=0)
    assert servidor.puerto_correcciones and servidor.puerto_correcciones != servidor.puerto

    with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=3) as posicion:
        with socket.create_connection(("127.0.0.1", servidor.puerto_correcciones), timeout=3) as correcciones:
            correcciones.sendall(bytes(range(256)) * 4)
            assert _esperar(lambda: servidor.bytes_correcciones == 1024)
        assert _leer_linea(posicion) == epocas[0][0]  # la posición sigue


def test_s021_un_corte_cierra_a_los_clientes_rechaza_un_tiempo_y_reanuda(servidores, epocas):
    """S021/Demo: el corte programado cierra la conexión, rechaza durante unos segundos y vuelve a aceptar."""
    servidor = _levantar(servidores, epocas, corte_cada=0.3, corte_durante=0.6)

    cliente = socket.create_connection(("127.0.0.1", servidor.puerto), timeout=3)
    try:
        assert _leer_linea(cliente)
        # El corte llega: se cierra la conexión (fin de flujo o error) y el puerto rechaza.
        def cortado() -> bool:
            try:
                cliente.settimeout(0.3)
                return cliente.recv(65536) == b"" and servidor.cortes >= 1
            except socket.timeout:
                return False
            except OSError:
                return True

        assert _esperar(cortado)
    finally:
        cliente.close()
    assert _esperar(lambda: servidor.cortes >= 1)

    def rechaza() -> bool:
        try:
            socket.create_connection(("127.0.0.1", servidor.puerto), timeout=0.3).close()
            return False
        except OSError:
            return True

    assert _esperar(rechaza, 3.0)

    def reanuda() -> bool:
        try:
            with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=0.5) as nuevo:
                return _leer_linea(nuevo, 1.0).startswith(b"$")
        except OSError:
            return False

    assert _esperar(reanuda, 6.0)


def test_s021_un_fichero_sin_frases_no_se_sirve(tmp_path):
    """S021/Demo: sin frases NMEA el servidor se niega a arrancar."""
    vacio = tmp_path / "vacio.nmea"
    vacio.write_bytes(b"basura sin frases\n")

    with pytest.raises(ValueError):
        s.Servidor(s.leer_epocas(vacio))
