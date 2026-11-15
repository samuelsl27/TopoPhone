"""La interfaz web de un receptor, de mentira, para probar la vista web de TopoPhone en el emulador o en el móvil (S043).

Sirve por HTTP, con la biblioteca estándar, lo mínimo que hace falta para ver los casos de la vista web de la app:
- `/`: una página con su título y un enlace a una dirección pública (la app no debe seguirlo, S043/RF-14);
- `/lento`: tarda `--lento` segundos en responder (con más de 10 s la app muestra el fallo con sus causas, S043/RF-16);
- `/fallo`: responde 500;
- `/redireccion`: redirige a una dirección pública (la app no debe seguir la redirección, S043/RF-14);
- cualquier otra ruta: 404.

Desde el emulador de Android, el PC es 10.0.2.2: en TopoPhone, «Datos del receptor» no hace falta; con un receptor Bluetooth, la
interfaz web pide la IP (`10.0.2.2:8080`), y con uno Wi-Fi usa el puerto 80, así que se lanza con `--puerto 80`.

Uso: uv run --project tools tools/simulador/servidor_web.py [--puerto 8080] [--host 0.0.0.0] [--lento 15]
"""

from __future__ import annotations

import argparse
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIRECCION_PUBLICA = "http://8.8.8.8/"

PAGINA = (
    "<!doctype html>\n"
    '<html lang="es">\n'
    "<head><meta charset=\"utf-8\"><title>Panel del receptor (simulado)</title></head>\n"
    "<body>\n"
    "<h1>Panel del receptor (simulado)</h1>\n"
    "<p>Esta página la sirve tools/simulador/servidor_web.py para probar la vista web de TopoPhone.</p>\n"
    f'<p><a id="publico" href="{DIRECCION_PUBLICA}">Un enlace a una dirección pública</a></p>\n'
    '<p><a id="lento" href="/lento">Una página lenta</a> · <a id="fallo" href="/fallo">Una página con fallo</a> · '
    '<a id="redireccion" href="/redireccion">Una redirección</a></p>\n'
    "</body>\n"
    "</html>\n"
)


def _manejador(lento_s: float, destino_redireccion: str) -> type[BaseHTTPRequestHandler]:
    class Manejador(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _responder(self, codigo: int, cuerpo: bytes, tipo: str = "text/html; charset=utf-8", cabeceras: dict[str, str] | None = None):
            self.send_response(codigo)
            self.send_header("Content-Type", tipo)
            self.send_header("Content-Length", str(len(cuerpo)))
            self.send_header("Connection", "close")
            for nombre, valor in (cabeceras or {}).items():
                self.send_header(nombre, valor)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(cuerpo)

        def do_GET(self) -> None:  # noqa: N802 - nombre fijado por http.server
            ruta = self.path.split("?", 1)[0]
            if ruta == "/":
                self._responder(200, PAGINA.encode("utf-8"))
            elif ruta == "/lento":
                time.sleep(lento_s)
                self._responder(200, PAGINA.encode("utf-8"))
            elif ruta == "/fallo":
                self._responder(500, "Fallo del receptor (simulado)\n".encode("utf-8"), "text/plain; charset=utf-8")
            elif ruta == "/redireccion":
                self._responder(302, b"", "text/plain; charset=utf-8", {"Location": destino_redireccion})
            else:
                self._responder(404, "No existe\n".encode("utf-8"), "text/plain; charset=utf-8")

        do_HEAD = do_GET  # noqa: N815

        def log_message(self, formato: str, *args) -> None:  # silencio: la demo no necesita el registro de cada petición
            return

    return Manejador


class Servidor:
    """El servidor web de la demo: se inicia en un hilo y se detiene limpiamente."""

    def __init__(
        self,
        puerto: int = 0,
        host: str = "127.0.0.1",
        lento_s: float = 15.0,
        destino_redireccion: str = DIRECCION_PUBLICA,
    ) -> None:
        self._http = ThreadingHTTPServer((host, puerto), _manejador(lento_s, destino_redireccion))
        self._http.daemon_threads = True
        self._hilo: threading.Thread | None = None

    @property
    def host(self) -> str:
        return self._http.server_address[0]

    @property
    def puerto(self) -> int:
        return self._http.server_address[1]

    def iniciar(self) -> Servidor:
        self._hilo = threading.Thread(target=self._http.serve_forever, name="servidor-web", daemon=True)
        self._hilo.start()
        return self

    def detener(self) -> None:
        self._http.shutdown()
        self._http.server_close()
        if self._hilo is not None:
            self._hilo.join(timeout=5)


def main() -> None:
    parser = argparse.ArgumentParser(description="La interfaz web de un receptor, de mentira (S043).")
    parser.add_argument("--puerto", type=int, default=8080)
    parser.add_argument("--host", default="0.0.0.0", help="0.0.0.0 para que el móvil o el emulador lleguen al PC")
    parser.add_argument("--lento", type=float, default=15.0, help="segundos que tarda /lento (más de 10 s da el fallo de la app)")
    argumentos = parser.parse_args()
    servidor = Servidor(argumentos.puerto, argumentos.host, argumentos.lento)
    print(f"Interfaz web de mentira en http://{servidor.host}:{servidor.puerto}/ (Ctrl+C para parar)")
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
