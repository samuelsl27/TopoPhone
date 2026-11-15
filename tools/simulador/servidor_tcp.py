"""Un receptor Wi-Fi de mentira para probar la conexión TCP de TopoPhone en el emulador o en el móvil (S021).

Sirve por TCP el NMEA de una sesión de `fixtures/nmea/` en bucle, una época cada `--periodo` segundos (como la salida de posición
por TCP de un receptor), y puede:
- cortar a los clientes cada cierto tiempo y no aceptar durante unos segundos, para ver la reconexión de la app (S004/RF-13);
- abrir un segundo puerto de «entrada de correcciones» que cuenta lo que le llega (las RTCM3 de la app, S021/RF-11).

Solo usa la biblioteca estándar. Desde el emulador de Android, el PC es 10.0.2.2: en TopoPhone, «Añadir receptor» → «Wi-Fi (TCP)»,
dirección 10.0.2.2 y puerto 9001 (y 9002 como puerto de correcciones).

Uso: uv run --project tools tools/simulador/servidor_tcp.py [--puerto 9001] [--puerto-correcciones 9002]
                                                           [--corte-cada 20 --corte-durante 8] [--nmea fichero.nmea] [--periodo 0.2]
"""

from __future__ import annotations

import argparse
import socket
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NMEA_POR_DEFECTO = ROOT / "fixtures" / "nmea" / "sintetico" / "rs3-completo.nmea"


def leer_epocas(ruta: Path) -> list[list[bytes]]:
    """Las frases del fichero agrupadas en épocas: cada GGA abre una (las anteriores a la primera GGA van con ella)."""
    epocas: list[list[bytes]] = []
    actual: list[bytes] = []
    for linea in Path(ruta).read_bytes().splitlines():
        frase = linea.strip()
        if not frase.startswith(b"$"):
            continue
        if frase[3:6] == b"GGA" and actual and any(f[3:6] == b"GGA" for f in actual):
            epocas.append(actual)
            actual = []
        actual.append(frase + b"\r\n")
    if actual:
        epocas.append(actual)
    return epocas


class Servidor:
    """El servidor de posición, con su puerto de correcciones opcional y los cortes programados."""

    def __init__(
        self,
        epocas: list[list[bytes]],
        puerto: int = 0,
        puerto_correcciones: int | None = None,
        periodo: float = 0.2,
        corte_cada: float | None = None,
        corte_durante: float = 5.0,
        host: str = "127.0.0.1",
    ) -> None:
        if not epocas:
            raise ValueError("el fichero NMEA no tiene frases")
        self.epocas = epocas
        self.host = host
        self.periodo = periodo
        self.corte_cada = corte_cada
        self.corte_durante = corte_durante
        self._puerto_pedido = puerto
        self._correcciones_pedido = puerto_correcciones
        self._parar = threading.Event()
        self._bloqueo = threading.Lock()
        self._clientes: list[socket.socket] = []
        self._escucha: socket.socket | None = None
        self._escucha_correcciones: socket.socket | None = None
        self.puerto = 0
        self.puerto_correcciones: int | None = None
        self.conexiones = 0
        self.cortes = 0
        self.bytes_correcciones = 0

    # ---- ciclo de vida
    def iniciar(self) -> "Servidor":
        self._escucha = self._abrir(self._puerto_pedido)
        self.puerto = self._escucha.getsockname()[1]
        threading.Thread(target=self._aceptar, daemon=True).start()
        if self._correcciones_pedido is not None:
            self._escucha_correcciones = self._abrir(self._correcciones_pedido)
            self.puerto_correcciones = self._escucha_correcciones.getsockname()[1]
            threading.Thread(target=self._aceptar_correcciones, daemon=True).start()
        if self.corte_cada:
            threading.Thread(target=self._cortes, daemon=True).start()
        return self

    def detener(self) -> None:
        self._parar.set()
        for escucha in (self._escucha, self._escucha_correcciones):
            if escucha is not None:
                _cerrar(escucha)
        self._cortar_clientes()

    def _abrir(self, puerto: int) -> socket.socket:
        escucha = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        escucha.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        escucha.bind((self.host, puerto))
        escucha.listen(5)
        return escucha

    # ---- posición
    def _aceptar(self) -> None:
        while not self._parar.is_set():
            escucha = self._escucha
            if escucha is None:
                time.sleep(0.05)
                continue
            try:
                cliente, _ = escucha.accept()
            except OSError:
                time.sleep(0.05)  # cerrado por un corte o por detener()
                continue
            self.conexiones += 1
            with self._bloqueo:
                self._clientes.append(cliente)
            threading.Thread(target=self._servir, args=(cliente,), daemon=True).start()

    def _servir(self, cliente: socket.socket) -> None:
        try:
            while not self._parar.is_set():
                for epoca in self.epocas:
                    for frase in epoca:
                        cliente.sendall(frase)
                    time.sleep(self.periodo)
        except OSError:
            pass  # el cliente se fue o hubo un corte
        finally:
            _cerrar(cliente)

    def _cortar_clientes(self) -> None:
        with self._bloqueo:
            clientes, self._clientes = self._clientes, []
        for cliente in clientes:
            _cerrar(cliente)

    def _cortes(self) -> None:
        """Cada `corte_cada` s: corta a los clientes y no acepta durante `corte_durante` s (puerto cerrado: rechazo inmediato)."""
        while not self._parar.wait(self.corte_cada):
            puerto = self.puerto
            _cerrar(self._escucha)
            self._escucha = None
            self._cortar_clientes()
            self.cortes += 1
            print(f"[corte {self.cortes}] sin servicio durante {self.corte_durante} s", flush=True)
            if self._parar.wait(self.corte_durante):
                return
            self._escucha = self._abrir(puerto)
            print("[corte] servicio restablecido", flush=True)

    # ---- correcciones
    def _aceptar_correcciones(self) -> None:
        while not self._parar.is_set():
            try:
                cliente, _ = self._escucha_correcciones.accept()
            except OSError:
                return
            threading.Thread(target=self._contar, args=(cliente,), daemon=True).start()

    def _contar(self, cliente: socket.socket) -> None:
        try:
            while True:
                datos = cliente.recv(4096)
                if not datos:
                    return
                self.bytes_correcciones += len(datos)
        except OSError:
            return
        finally:
            _cerrar(cliente)


def _cerrar(conexion: socket.socket | None) -> None:
    if conexion is None:
        return
    try:
        conexion.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
    try:
        conexion.close()
    except OSError:
        pass


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--nmea", type=Path, default=NMEA_POR_DEFECTO, help="sesión NMEA que se sirve en bucle")
    ap.add_argument("--puerto", type=int, default=9001, help="puerto de posición (como la salida TCP del receptor)")
    ap.add_argument("--puerto-correcciones", type=int, default=None, help="puerto que recibe las correcciones (opcional)")
    ap.add_argument("--periodo", type=float, default=0.2, help="segundos entre épocas (0,2 = 5 Hz)")
    ap.add_argument("--corte-cada", type=float, default=None, help="cada cuántos segundos cortar a los clientes")
    ap.add_argument("--corte-durante", type=float, default=8.0, help="segundos sin aceptar conexiones tras cada corte")
    ap.add_argument("--host", default="0.0.0.0", help="dirección donde escuchar (0.0.0.0 = también el emulador)")
    args = ap.parse_args()
    servidor = Servidor(
        leer_epocas(args.nmea),
        args.puerto,
        args.puerto_correcciones,
        args.periodo,
        args.corte_cada,
        args.corte_durante,
        args.host,
    ).iniciar()
    print(f"Posición en {args.host}:{servidor.puerto}" + (f", correcciones en :{servidor.puerto_correcciones}" if servidor.puerto_correcciones else ""))
    ultimo = 0
    try:
        while True:
            time.sleep(1)
            if servidor.bytes_correcciones != ultimo:
                ultimo = servidor.bytes_correcciones
                print(f"correcciones recibidas: {ultimo} bytes", flush=True)
    except KeyboardInterrupt:
        servidor.detener()


if __name__ == "__main__":
    main()
