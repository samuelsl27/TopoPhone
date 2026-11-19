"""La API no oficial de un receptor, de mentira, para probar el módulo experimental de TopoPhone en el emulador o en el móvil (S044).

Sirve, con la biblioteca estándar y por el mismo puerto (80 por defecto, el del receptor), lo que el módulo lee y escribe. Las formas salen
del código de referencia que recoge `docs/referencias/emlid/enlaces.md` y **no están verificadas contra un receptor real**:
- `GET /info`: `device.model`, `device.serial_number` y `device.app_version`;
- `GET /configuration`: `configuration.device` (rol y `antenna_height`, en metros), `positioning_settings` y secciones de salida;
- `POST /configuration/device`: el objeto `device` completo; lo que se envíe es lo que se lee después;
- `POST /configuration/logging/logs` con `{"started": true|false}`;
- `GET /socket.io/?EIO=3&transport=websocket`: el directo (Engine.IO v3): `0{…}` al abrir, `40`, y eventos `broadcast` con
  `battery_status` y `active_logs`; responde `3` al `2` del cliente y anuncia cada cambio de los logs.

Opciones para ver los casos de la spec: `--incompatible` (respuestas con otra forma, RF-15), `--lento S` (las lecturas tardan S segundos,
más de 3 s da «Receptor no compatible», RF-15), `--cortar-directo N` (cierra el directo cada N segundos, RF-17) y `--sin-logs` (no hay
eventos de logs y el POST da 404, RF-20).

Desde el emulador de Android, el PC es 10.0.2.2: da de alta un receptor Wi-Fi con esa dirección (puerto de posición el que sea; el módulo
habla siempre con el 80, así que se lanza con `--puerto 80`). No hay mDNS: la búsqueda de receptores no se puede ver en el emulador.

Uso: uv run --project tools tools/simulador/servidor_reach.py [--puerto 80] [--host 0.0.0.0] [--incompatible] [--lento 5]
                                                               [--cortar-directo 10] [--sin-logs]
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import select
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

GUID_WEBSOCKET = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
RUTA_DIRECTO = "/socket.io/"
INTERVALO_PING_MS = 25_000
ESPERA_PING_MS = 60_000


def aceptacion_websocket(clave: str) -> str:
    """La cabecera `Sec-WebSocket-Accept` del apretón de manos (RFC 6455)."""
    return base64.b64encode(hashlib.sha1((clave + GUID_WEBSOCKET).encode("ascii")).digest()).decode("ascii")


def _fotograma(codigo: int, carga: bytes) -> bytes:
    """Un fotograma de servidor (sin máscara)."""
    n = len(carga)
    if n < 126:
        cabecera = bytes([0x80 | codigo, n])
    elif n < 65_536:
        cabecera = bytes([0x80 | codigo, 126]) + n.to_bytes(2, "big")
    else:
        cabecera = bytes([0x80 | codigo, 127]) + n.to_bytes(8, "big")
    return cabecera + carga


def _leer_exacto(conexion: socket.socket, cuantos: int) -> bytes:
    datos = b""
    while len(datos) < cuantos:
        trozo = conexion.recv(cuantos - len(datos))
        if not trozo:
            raise ConnectionError("el cliente cerró")
        datos += trozo
    return datos


def _leer_fotograma(conexion: socket.socket) -> tuple[int, bytes]:
    """Un fotograma de cliente (con máscara): (código de operación, carga)."""
    cabecera = _leer_exacto(conexion, 2)
    codigo, enmascarado, longitud = cabecera[0] & 0x0F, bool(cabecera[1] & 0x80), cabecera[1] & 0x7F
    if longitud == 126:
        longitud = int.from_bytes(_leer_exacto(conexion, 2), "big")
    elif longitud == 127:
        longitud = int.from_bytes(_leer_exacto(conexion, 8), "big")
    mascara = _leer_exacto(conexion, 4) if enmascarado else b"\x00\x00\x00\x00"
    carga = _leer_exacto(conexion, longitud)
    return codigo, bytes(b ^ mascara[i % 4] for i, b in enumerate(carga))


class _Directo:
    """Una conexión de directo abierta: escribir en ella desde varios hilos va con cerrojo."""

    def __init__(self, conexion: socket.socket) -> None:
        self.conexion = conexion
        self._cerrojo = threading.Lock()

    def texto(self, contenido: str) -> None:
        self.enviar(_fotograma(0x1, contenido.encode("utf-8")))

    def enviar(self, bruto: bytes) -> None:
        with self._cerrojo:
            self.conexion.sendall(bruto)


def _evento(nombre: str, carga: dict[str, Any]) -> str:
    return "42" + json.dumps(["broadcast", {"name": nombre, "payload": carga}])


class Servidor:
    """El receptor de la demo: se inicia en un hilo y se detiene limpiamente."""

    def __init__(
        self,
        puerto: int = 0,
        host: str = "127.0.0.1",
        incompatible: bool = False,
        lento_s: float = 0.0,
        cortar_directo_s: float = 0.0,
        sin_logs: bool = False,
    ) -> None:
        self.incompatible = incompatible
        self.lento_s = lento_s
        self.cortar_directo_s = cortar_directo_s
        self.sin_logs = sin_logs
        self.bateria = 87
        self.logs_escribiendo = False
        self.escrituras_dispositivo: list[dict[str, Any]] = []
        self.escrituras_logs: list[bool] = []
        self._dispositivo: dict[str, Any] = {"role": "rover", "antenna_height": 1.8, "night_mode": False}
        self._cerrojo = threading.Lock()
        self._directos: list[_Directo] = []
        self._http = ThreadingHTTPServer((host, puerto), self._manejador())
        self._http.daemon_threads = True
        self._hilo: threading.Thread | None = None

    @property
    def host(self) -> str:
        return self._http.server_address[0]

    @property
    def puerto(self) -> int:
        return self._http.server_address[1]

    # --- lo que cuenta el receptor -------------------------------------------------------------------------------------------------

    def info(self) -> dict[str, Any]:
        if self.incompatible:
            return {"device": {"model": "Receptor simulado", "app_version": 33}}
        return {"device": {"model": "Reach RS3 (simulado)", "serial_number": "SIMULADO0001", "app_version": "v0.0-simulado"}}

    def configuracion(self) -> dict[str, Any]:
        with self._cerrojo:
            dispositivo = dict(self._dispositivo)
        if self.incompatible:
            dispositivo["antenna_height"] = "alta"
        return {
            "configuration": {
                "device": dispositivo,
                "positioning_settings": {
                    "positioning_mode": "kinematic",
                    "gps_ar_mode": "fix-and-hold",
                    "elevation_mask_angle": 15,
                    "snr_mask": 35,
                    "gnss_settings": {
                        "update_rate": 5,
                        "positioning_systems": {"gps": True, "glonass": True, "galileo": True, "beidou": False, "qzss": False},
                    },
                },
                "correction_input": {"input2": {"type": "ntrip", "mountpoint": "SIMULADO", "password": "clave-de-mentira"}},
                "position_output": {
                    "output1": {"type": "bluetooth", "format": "nmea", "sentences": ["GGA", "GST"]},
                    "output2": {"type": "tcp", "role": "server", "port": 9001, "token": "token-de-mentira"},
                },
            }
        }

    def escribir_dispositivo(self, dispositivo: dict[str, Any]) -> None:
        with self._cerrojo:
            self._dispositivo = dict(dispositivo)
            self.escrituras_dispositivo.append(dict(dispositivo))

    def escribir_logs(self, iniciar: bool) -> None:
        with self._cerrojo:
            self.logs_escribiendo = iniciar
            self.escrituras_logs.append(iniciar)
        self.difundir(_evento("active_logs", self.carga_logs()))

    def carga_bateria(self) -> dict[str, Any]:
        return {"state_of_charge": self.bateria, "charger_status": "discharging"}

    def carga_logs(self) -> dict[str, Any]:
        return {
            "raw": {"is_writing": self.logs_escribiendo},
            "solution": {"is_writing": False},
            "base": {"is_writing": False},
        }

    def difundir(self, texto: str) -> None:
        if self.sin_logs and "active_logs" in texto:
            return
        with self._cerrojo:
            directos = list(self._directos)
        for directo in directos:
            try:
                directo.texto(texto)
            except OSError:
                pass

    # --- arranque y parada ---------------------------------------------------------------------------------------------------------

    def iniciar(self) -> Servidor:
        self._hilo = threading.Thread(target=self._http.serve_forever, name="servidor-reach", daemon=True)
        self._hilo.start()
        return self

    def detener(self) -> None:
        self._http.shutdown()
        self._http.server_close()
        with self._cerrojo:
            directos = list(self._directos)
        for directo in directos:
            try:
                directo.conexion.close()
            except OSError:
                pass
        if self._hilo is not None:
            self._hilo.join(timeout=5)

    # --- HTTP ----------------------------------------------------------------------------------------------------------------------

    def _manejador(self) -> type[BaseHTTPRequestHandler]:
        servidor = self

        class Manejador(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def _responder(self, codigo: int, contenido: Any = None) -> None:
                cuerpo = json.dumps(contenido if contenido is not None else {}).encode("utf-8")
                self.send_response(codigo)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(cuerpo)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(cuerpo)

            def _cuerpo_json(self) -> Any:
                longitud = int(self.headers.get("Content-Length", "0"))
                try:
                    return json.loads(self.rfile.read(longitud).decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    return None

            def do_GET(self) -> None:  # noqa: N802 - nombre fijado por http.server
                ruta = self.path.split("?", 1)[0]
                if ruta == RUTA_DIRECTO and self.headers.get("Upgrade", "").lower() == "websocket":
                    self._directo()
                    return
                if ruta in ("/info", "/configuration"):
                    if servidor.lento_s:
                        time.sleep(servidor.lento_s)
                    self._responder(200, servidor.info() if ruta == "/info" else servidor.configuracion())
                else:
                    self._responder(404, {"error": "no existe"})

            def do_POST(self) -> None:  # noqa: N802
                ruta = self.path.split("?", 1)[0]
                cuerpo = self._cuerpo_json()
                if ruta == "/configuration/device":
                    if not isinstance(cuerpo, dict):
                        self._responder(400, {"error": "se esperaba un objeto"})
                        return
                    servidor.escribir_dispositivo(cuerpo)
                    self._responder(200)
                elif ruta == "/configuration/logging/logs" and not servidor.sin_logs:
                    if not isinstance(cuerpo, dict) or not isinstance(cuerpo.get("started"), bool):
                        self._responder(400, {"error": "se esperaba started booleano"})
                        return
                    servidor.escribir_logs(cuerpo["started"])
                    self._responder(200)
                else:
                    self._responder(404, {"error": "no existe"})

            def _directo(self) -> None:
                clave = self.headers.get("Sec-WebSocket-Key", "")
                self.send_response(101, "Switching Protocols")
                self.send_header("Upgrade", "websocket")
                self.send_header("Connection", "Upgrade")
                self.send_header("Sec-WebSocket-Accept", aceptacion_websocket(clave))
                self.end_headers()
                self.wfile.flush()
                self.close_connection = True
                directo = _Directo(self.connection)
                with servidor._cerrojo:
                    servidor._directos.append(directo)
                try:
                    directo.texto("0" + json.dumps({"sid": "SIMULADO", "upgrades": [], "pingInterval": INTERVALO_PING_MS, "pingTimeout": ESPERA_PING_MS}))
                    directo.texto("40")
                    directo.texto(_evento("battery_status", servidor.carga_bateria()))
                    if not servidor.sin_logs:
                        directo.texto(_evento("active_logs", servidor.carga_logs()))
                    self._atender(directo)
                except (OSError, ConnectionError):
                    pass
                finally:
                    with servidor._cerrojo:
                        if directo in servidor._directos:
                            servidor._directos.remove(directo)

            def _atender(self, directo: _Directo) -> None:
                limite = time.monotonic() + servidor.cortar_directo_s if servidor.cortar_directo_s else None
                while True:
                    if limite is not None and time.monotonic() >= limite:
                        directo.enviar(_fotograma(0x8, b"\x03\xe8"))
                        return
                    if not select.select([directo.conexion], [], [], 0.1)[0]:
                        continue
                    codigo, carga = _leer_fotograma(directo.conexion)
                    if codigo == 0x8:
                        directo.enviar(_fotograma(0x8, b""))
                        return
                    if codigo == 0x9:
                        directo.enviar(_fotograma(0xA, carga))
                    elif codigo == 0x1 and carga == b"2":
                        directo.texto("3")

            def log_message(self, formato: str, *args) -> None:  # silencio: la demo no necesita el registro de cada petición
                return

        return Manejador


def main() -> None:
    parser = argparse.ArgumentParser(description="La API no oficial de un receptor, de mentira (S044).")
    parser.add_argument("--puerto", type=int, default=80, help="80: el puerto con el que habla el módulo")
    parser.add_argument("--host", default="0.0.0.0", help="0.0.0.0 para que el móvil o el emulador lleguen al PC")
    parser.add_argument("--incompatible", action="store_true", help="respuestas con otra forma (RF-15)")
    parser.add_argument("--lento", type=float, default=0.0, help="segundos que tardan las lecturas (más de 3 s dan el fallo, RF-15)")
    parser.add_argument("--cortar-directo", type=float, default=0.0, help="cierra el directo cada N segundos (RF-17)")
    parser.add_argument("--sin-logs", action="store_true", help="sin eventos de logs y el POST da 404 (RF-20)")
    argumentos = parser.parse_args()
    servidor = Servidor(
        argumentos.puerto,
        argumentos.host,
        incompatible=argumentos.incompatible,
        lento_s=argumentos.lento,
        cortar_directo_s=argumentos.cortar_directo,
        sin_logs=argumentos.sin_logs,
    )
    print(f"Receptor de mentira en http://{servidor.host}:{servidor.puerto}/ (Ctrl+C para parar)")
    try:
        servidor.iniciar()
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        servidor.detener()


if __name__ == "__main__":
    main()
