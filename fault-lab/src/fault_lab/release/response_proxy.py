from __future__ import annotations

import json
import os
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


_MODES = {"NORMAL", "AUTH", "SCHEMA"}
_HOP_HEADERS = {
    "connection",
    "content-length",
    "host",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
}


class RouteState:
    def __init__(self, routes: dict[str, dict[str, Any]]):
        self.routes = routes
        self._modes = {route["routeId"]: "NORMAL" for route in routes.values()}
        self._lock = threading.Lock()

    def mode(self, route_id: str) -> str:
        with self._lock:
            return self._modes[route_id]

    def set_mode(self, route_id: str, mode: str) -> None:
        if route_id not in self._modes or mode not in _MODES:
            raise ValueError("unknown route or mode")
        with self._lock:
            self._modes[route_id] = mode

    def snapshot(self) -> dict[str, str]:
        with self._lock:
            return dict(self._modes)


def main() -> int:
    routes = _routes_from_environment()
    state = RouteState(routes)
    servers: list[ThreadingHTTPServer] = []
    for port, route in routes.items():
        handler = _route_handler(state, route["routeId"], route["upstream"])
        servers.append(ThreadingHTTPServer(("0.0.0.0", int(port)), handler))
    control_port = int(os.environ.get("PHASE8_RESPONSE_CONTROL_PORT", "18000"))
    servers.append(ThreadingHTTPServer(("0.0.0.0", control_port), _control_handler(state)))
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()
    try:
        threads[-1].join()
    except KeyboardInterrupt:
        pass
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
    return 0


def _routes_from_environment() -> dict[str, dict[str, str]]:
    try:
        value = json.loads(os.environ["PHASE8_RESPONSE_ROUTES"])
    except (KeyError, json.JSONDecodeError) as exception:
        raise SystemExit("PHASE8_RESPONSE_ROUTES_INVALID") from exception
    if not isinstance(value, dict) or not value:
        raise SystemExit("PHASE8_RESPONSE_ROUTES_INVALID")
    routes: dict[str, dict[str, str]] = {}
    route_ids: set[str] = set()
    for port, route in value.items():
        if (
            not str(port).isdigit()
            or not isinstance(route, dict)
            or set(route) != {"routeId", "upstream"}
            or not all(isinstance(route[field], str) and route[field] for field in route)
            or route["routeId"] in route_ids
        ):
            raise SystemExit("PHASE8_RESPONSE_ROUTES_INVALID")
        routes[str(port)] = dict(route)
        route_ids.add(route["routeId"])
    return routes


def _route_handler(state: RouteState, route_id: str, upstream: str):
    class RouteHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self._handle()

        def do_HEAD(self) -> None:
            self._handle()

        def do_POST(self) -> None:
            self._handle()

        def do_PUT(self) -> None:
            self._handle()

        def do_PATCH(self) -> None:
            self._handle()

        def do_DELETE(self) -> None:
            self._handle()

        def _handle(self) -> None:
            mode = state.mode(route_id)
            if mode == "AUTH":
                self._json(401, {"error": {"code": "PHASE8_AUTH_REJECTED"}})
                return
            if mode == "SCHEMA":
                self._json(200, {"phase8Malformed": True})
                return
            self._forward()

        def _forward(self) -> None:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length) if length else None
            request = urllib.request.Request(
                upstream.rstrip("/") + self.path,
                data=body,
                headers={
                    name: value
                    for name, value in self.headers.items()
                    if name.lower() not in _HOP_HEADERS
                },
                method=self.command,
            )
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    payload = response.read()
                    self._response(response.status, response.headers.items(), payload)
            except urllib.error.HTTPError as error:
                self._response(error.code, error.headers.items(), error.read())
            except (OSError, urllib.error.URLError) as error:
                self._json(502, {"error": {"code": "PHASE8_UPSTREAM_UNAVAILABLE", "type": type(error).__name__}})

        def _response(self, status: int, headers, body: bytes) -> None:
            self.send_response(status)
            for name, value in headers:
                if name.lower() not in _HOP_HEADERS:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, status: int, value: dict[str, Any]) -> None:
            body = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
            self._response(status, (("Content-Type", "application/json"),), body)

        def log_message(self, format: str, *args: Any) -> None:
            return

    return RouteHandler


def _control_handler(state: RouteState):
    class ControlHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path != "/health":
                self._json(404, {"code": "PHASE8_CONTROL_ROUTE_NOT_FOUND"})
                return
            self._json(200, {"status": "UP", "routes": state.snapshot()})

        def do_PUT(self) -> None:
            route_id = self._route_id()
            if route_id is None:
                self._json(404, {"code": "PHASE8_CONTROL_ROUTE_NOT_FOUND"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                value = json.loads(self.rfile.read(length))
                state.set_mode(route_id, value["mode"])
            except (KeyError, ValueError, json.JSONDecodeError):
                self._json(400, {"code": "PHASE8_CONTROL_REQUEST_INVALID"})
                return
            self._json(200, {"routeId": route_id, "mode": state.mode(route_id)})

        def do_DELETE(self) -> None:
            route_id = self._route_id()
            if route_id is None:
                self._json(404, {"code": "PHASE8_CONTROL_ROUTE_NOT_FOUND"})
                return
            state.set_mode(route_id, "NORMAL")
            self._json(200, {"routeId": route_id, "mode": "NORMAL"})

        def _route_id(self) -> str | None:
            prefix = "/routes/"
            route_id = self.path[len(prefix) :] if self.path.startswith(prefix) else ""
            return route_id if route_id in state.snapshot() else None

        def _json(self, status: int, value: dict[str, Any]) -> None:
            body = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: Any) -> None:
            return

    return ControlHandler


if __name__ == "__main__":
    raise SystemExit(main())
