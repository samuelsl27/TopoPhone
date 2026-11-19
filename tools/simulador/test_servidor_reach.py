"""Tests del receptor de mentira para el módulo experimental (S044): HTTP, WebSocket de Engine.IO v3 y las opciones de fallo."""

from __future__ import annotations

import base64
import hashlib
import http.client
import json
import os
import socket
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import servidor_reach as r  # noqa: E402

RUTA_DIRECTO = "/socket.io/?EIO=3&transport=websocket"
CLAVE = "dGhlIHNhbXBsZSBub25jZQ=="  # el ejemplo del RFC 6455
ACEPTA = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="


@pytest.fixture
def servidores():
    creados: list[r.Servidor] = []
    yield creados
    for servidor in creados:
        servidor.detener()


def _levantar(servidores, **opciones) -> r.Servidor:
    servidor = r.Servidor(**opciones).iniciar()
    servidores.append(servidor)
    return servidor


def _http(servidor: r.Servidor, metodo: str, ruta: str, cuerpo: str | None = None, plazo: float = 10.0):
    conexion = http.client.HTTPConnection(servidor.host, servidor.puerto, timeout=plazo)
    try:
        cabeceras = {"Content-Type": "application/json"} if cuerpo is not None else {}
        conexion.request(metodo, ruta, body=cuerpo, headers=cabeceras)
        respuesta = conexion.getresponse()
        return respuesta.status, respuesta.read()
    finally:
        conexion.close()


def _json(servidor: r.Servidor, ruta: str):
    estado, cuerpo = _http(servidor, "GET", ruta)
    assert estado == 200
    return json.loads(cuerpo)


class Directo:
    """Un cliente WebSocket mínimo (con máscara, como exige el protocolo para los clientes)."""

    def __init__(self, servidor: r.Servidor, clave: str = CLAVE, plazo: float = 5.0) -> None:
        self.socket = socket.create_connection((servidor.host, servidor.puerto), timeout=plazo)
        peticion = (
            f"GET {RUTA_DIRECTO} HTTP/1.1\r\nHost: {servidor.host}:{servidor.puerto}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {clave}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        self.socket.sendall(peticion.encode("ascii"))
        self.lector = self.socket.makefile("rb")
        self.estado = self.lector.readline().decode("ascii").strip()
        self.cabeceras: dict[str, str] = {}
        while (linea := self.lector.readline().decode("ascii").strip()) != "":
            nombre, _, valor = linea.partition(":")
            self.cabeceras[nombre.strip().lower()] = valor.strip()

    def leer(self) -> tuple[int, str]:
        """El siguiente fotograma del servidor (sin máscara): (código de operación, texto)."""
        cabecera = self.lector.read(2)
        if len(cabecera) < 2:
            return -1, ""
        codigo, longitud = cabecera[0] & 0x0F, cabecera[1] & 0x7F
        if longitud == 126:
            longitud = int.from_bytes(self.lector.read(2), "big")
        elif longitud == 127:
            longitud = int.from_bytes(self.lector.read(8), "big")
        return codigo, self.lector.read(longitud).decode("utf-8", errors="replace")

    def texto(self) -> str:
        codigo, contenido = self.leer()
        assert codigo == 1, f"se esperaba texto y llegó el código {codigo}"
        return contenido

    def enviar(self, contenido: str, codigo: int = 1) -> None:
        carga = contenido.encode("utf-8")
        mascara = os.urandom(4)
        enmascarada = bytes(b ^ mascara[i % 4] for i, b in enumerate(carga))
        self.socket.sendall(bytes([0x80 | codigo, 0x80 | len(carga)]) + mascara + enmascarada)

    def cerrar(self) -> None:
        self.socket.close()


def _evento(texto: str) -> tuple[str, dict]:
    assert texto.startswith("42")
    nombre, datos = json.loads(texto[2:])
    assert nombre == "broadcast"
    return datos["name"], datos["payload"]


def test_s044_rf_11_info_trae_modelo_serie_y_firmware_con_las_claves_de_la_referencia(servidores):
    """S044/RF-11: GET /info con device.model, device.serial_number y device.app_version."""
    dispositivo = _json(_levantar(servidores), "/info")["device"]

    assert dispositivo["model"] and dispositivo["serial_number"] and dispositivo["app_version"]
    assert all(isinstance(valor, str) for valor in dispositivo.values())


def test_s044_rf_11_configuration_trae_el_dispositivo_y_los_ajustes(servidores):
    """S044/RF-11, RF-14: rol y altura de antena (un número), posicionamiento y secciones de salida."""
    configuracion = _json(_levantar(servidores), "/configuration")["configuration"]

    assert isinstance(configuracion["device"]["antenna_height"], float)
    assert configuracion["device"]["role"] == "rover"
    assert "positioning_settings" in configuracion
    assert any("output" in nombre for nombre in configuracion)


def test_s044_rf_19_escribir_el_dispositivo_cambia_lo_que_se_lee_despues(servidores):
    """S044/RF-19: POST /configuration/device con el objeto completo; la relectura lo refleja."""
    servidor = _levantar(servidores)
    dispositivo = _json(servidor, "/configuration")["configuration"]["device"]
    dispositivo["antenna_height"] = 1.234

    estado, _ = _http(servidor, "POST", "/configuration/device", json.dumps(dispositivo))

    assert estado == 200
    assert _json(servidor, "/configuration")["configuration"]["device"]["antenna_height"] == 1.234
    assert servidor.escrituras_dispositivo == [dispositivo]


def test_s044_rf_21_un_cuerpo_que_no_es_un_objeto_json_se_rechaza_sin_cambiar_nada(servidores):
    """S044/RF-21: 400 y el estado no cambia."""
    servidor = _levantar(servidores)
    antes = _json(servidor, "/configuration")

    for cuerpo in ("esto no es json", "[1, 2]", "5"):
        estado, _ = _http(servidor, "POST", "/configuration/device", cuerpo)
        assert estado == 400

    assert _json(servidor, "/configuration") == antes


def test_s044_rf_13_el_directo_hace_el_apreton_de_manos_y_manda_apertura_conexion_bateria_y_logs(servidores):
    """S044/RF-13, RF-16: Engine.IO v3: `0{…}`, `40`, battery_status y active_logs."""
    directo = Directo(_levantar(servidores))

    assert "101" in directo.estado
    assert directo.cabeceras["sec-websocket-accept"] == ACEPTA
    apertura = directo.texto()
    assert apertura.startswith("0")
    datos = json.loads(apertura[1:])
    assert datos["pingInterval"] > 0 and datos["pingTimeout"] > 0
    assert directo.texto() == "40"
    nombre, carga = _evento(directo.texto())
    assert nombre == "battery_status" and isinstance(carga["state_of_charge"], int) and carga["charger_status"]
    nombre, carga = _evento(directo.texto())
    assert nombre == "active_logs" and carga["raw"]["is_writing"] is False
    directo.cerrar()


def test_s044_rf_16_el_ping_del_cliente_se_contesta_con_un_pong(servidores):
    """S044/RF-16: el cliente manda `2` y el servidor contesta `3`."""
    directo = Directo(_levantar(servidores))
    for _ in range(4):
        directo.texto()

    directo.enviar("2")

    assert directo.texto() == "3"
    directo.cerrar()


def test_s044_rf_20_iniciar_y_parar_los_logs_se_anuncia_por_el_directo(servidores):
    """S044/RF-20: POST /configuration/logging/logs y el evento active_logs con el estado resultante."""
    servidor = _levantar(servidores)
    directo = Directo(servidor)
    for _ in range(4):
        directo.texto()

    estado, _ = _http(servidor, "POST", "/configuration/logging/logs", json.dumps({"started": True}))
    assert estado == 200
    nombre, carga = _evento(directo.texto())
    assert nombre == "active_logs" and carga["raw"]["is_writing"] is True

    _http(servidor, "POST", "/configuration/logging/logs", json.dumps({"started": False}))
    assert _evento(directo.texto())[1]["raw"]["is_writing"] is False
    assert servidor.escrituras_logs == [True, False]
    directo.cerrar()


def test_s044_rf_20_un_cuerpo_sin_started_booleano_no_cambia_los_logs(servidores):
    """S044/RF-20: se exige `started` booleano."""
    servidor = _levantar(servidores)

    for cuerpo in ("{}", json.dumps({"started": "si"}), "no json"):
        estado, _ = _http(servidor, "POST", "/configuration/logging/logs", cuerpo)
        assert estado == 400

    assert servidor.escrituras_logs == []


def test_s044_rf_15_incompatible_cambia_la_forma_de_las_respuestas(servidores):
    """S044/RF-15: sin serie, con el firmware como número y la altura de antena como texto."""
    servidor = _levantar(servidores, incompatible=True)

    dispositivo = _json(servidor, "/info")["device"]
    assert "serial_number" not in dispositivo and isinstance(dispositivo["app_version"], int)
    assert isinstance(_json(servidor, "/configuration")["configuration"]["device"]["antenna_height"], str)


def test_s044_rf_15_lento_hace_esperar_las_lecturas(servidores):
    """S044/RF-15: con `lento`, las lecturas tardan más que el plazo de 3 s de la app."""
    servidor = _levantar(servidores, lento_s=0.6)
    inicio = time.monotonic()

    _http(servidor, "GET", "/info")

    assert time.monotonic() - inicio >= 0.6


def test_s044_rf_17_cortar_directo_cierra_el_websocket_pasado_el_tiempo(servidores):
    """S044/RF-17: el servidor cierra el directo cada N segundos para ver la reconexión."""
    directo = Directo(_levantar(servidores, cortar_directo_s=0.5), plazo=5.0)
    for _ in range(4):
        directo.texto()

    inicio = time.monotonic()
    codigo, _ = directo.leer()

    assert codigo in (8, -1)
    assert time.monotonic() - inicio < 4.0
    directo.cerrar()


def test_s044_rf_20_sin_logs_no_hay_evento_de_logs_y_el_envio_se_rechaza(servidores):
    """S044/RF-20: un receptor que no dice nada de los logs: no manda active_logs y no admite el POST."""
    servidor = _levantar(servidores, sin_logs=True)
    directo = Directo(servidor)
    assert directo.texto().startswith("0") and directo.texto() == "40"
    assert _evento(directo.texto())[0] == "battery_status"

    estado, _ = _http(servidor, "POST", "/configuration/logging/logs", json.dumps({"started": True}))

    assert estado == 404
    directo.socket.settimeout(0.5)
    with pytest.raises(socket.timeout):
        directo.leer()
    directo.cerrar()


def test_s044_las_rutas_que_no_existen_dan_404(servidores):
    """Cualquier otra ruta, 404."""
    servidor = _levantar(servidores)

    assert _http(servidor, "GET", "/nada")[0] == 404
    assert _http(servidor, "POST", "/nada", "{}")[0] == 404


def test_s044_el_accept_del_apreton_sigue_el_rfc_6455():
    """La clave de aceptación sale de SHA-1 de la clave y el GUID del protocolo."""
    esperado = base64.b64encode(hashlib.sha1((CLAVE + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()

    assert r.aceptacion_websocket(CLAVE) == esperado == ACEPTA
