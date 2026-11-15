"""Tests de la interfaz web de mentira de un receptor (S043): una página, un fallo, una redirección y una página lenta."""

from __future__ import annotations

import http.client
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import servidor_web as w  # noqa: E402


@pytest.fixture
def servidores():
    creados: list[w.Servidor] = []
    yield creados
    for servidor in creados:
        servidor.detener()


def _levantar(servidores, **opciones) -> w.Servidor:
    servidor = w.Servidor(**opciones).iniciar()
    servidores.append(servidor)
    return servidor


def _pedir(servidor: w.Servidor, ruta: str, plazo: float = 5.0) -> tuple[int, dict[str, str], bytes]:
    conexion = http.client.HTTPConnection(servidor.host, servidor.puerto, timeout=plazo)
    try:
        conexion.request("GET", ruta)
        respuesta = conexion.getresponse()
        return respuesta.status, {k.lower(): v for k, v in respuesta.getheaders()}, respuesta.read()
    finally:
        conexion.close()


def test_s043_rf_12_la_pagina_principal_se_sirve_con_su_titulo_en_utf8(servidores):
    """S043/RF-12: una página con título y los enlaces de la demo."""
    estado, cabeceras, cuerpo = _pedir(_levantar(servidores), "/")

    assert estado == 200
    assert cabeceras["content-type"] == "text/html; charset=utf-8"
    texto = cuerpo.decode("utf-8")
    assert "<title>Panel del receptor (simulado)</title>" in texto
    assert 'id="publico"' in texto and w.DIRECCION_PUBLICA in texto


def test_s043_rf_16_el_fallo_responde_500(servidores):
    """S043/RF-16: una página que falla, para ver el error de la vista."""
    estado, _, cuerpo = _pedir(_levantar(servidores), "/fallo")

    assert estado == 500
    assert b"Fallo" in cuerpo


def test_s043_rf_14_la_redireccion_apunta_a_una_direccion_publica_sin_seguirla(servidores):
    """S043/RF-14: el servidor redirige a una dirección pública; seguirla o no lo decide la app (no debe)."""
    estado, cabeceras, _ = _pedir(_levantar(servidores), "/redireccion")

    assert estado == 302
    assert cabeceras["location"] == w.DIRECCION_PUBLICA


def test_s043_rf_14_el_destino_de_la_redireccion_se_puede_elegir(servidores):
    """S043/RF-14: la demo también puede redirigir a otra IP privada."""
    servidor = _levantar(servidores, destino_redireccion="http://192.168.9.9/")

    _, cabeceras, _ = _pedir(servidor, "/redireccion")

    assert cabeceras["location"] == "http://192.168.9.9/"


def test_s043_rf_16_la_pagina_lenta_tarda_lo_pedido(servidores):
    """S043/RF-16: /lento espera el tiempo pedido antes de responder (la app corta a los 10 s)."""
    servidor = _levantar(servidores, lento_s=0.4)

    inicio = time.monotonic()
    estado, _, _ = _pedir(servidor, "/lento")

    assert estado == 200
    assert time.monotonic() - inicio >= 0.35


def test_s043_rf_16_una_ruta_desconocida_da_404_y_el_servidor_se_detiene_limpio(servidores):
    """S043/RF-16: lo demás es 404 y, al detenerlo, el puerto queda libre."""
    servidor = _levantar(servidores)
    puerto = servidor.puerto

    estado, _, _ = _pedir(servidor, "/no-existe")
    servidor.detener()

    assert estado == 404
    with pytest.raises(OSError):
        http.client.HTTPConnection("127.0.0.1", puerto, timeout=1).request("GET", "/")
