from __future__ import annotations

import json
import subprocess
import urllib.error
import urllib.request
from typing import Any, Callable, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode


CommandRunner = Callable[..., Any]
HttpReader = Callable[[str], tuple[int, Mapping[str, Any]]]


class RuntimeRecoveryProbe:
    """Verifies the live Compose identity and recovered health against one frozen snapshot."""

    def __init__(
        self,
        snapshot: Mapping[str, Any],
        compose_project: str,
        health_urls: tuple[str, ...],
        toxiproxy_url: str,
        command_runner: CommandRunner = subprocess.run,
        http_reader: HttpReader | None = None,
    ):
        self.snapshot = snapshot
        self.compose_project = compose_project
        self.health_urls = health_urls
        self.toxiproxy_url = toxiproxy_url.rstrip("/")
        self.command_runner = command_runner
        self.http_reader = http_reader or _http_json

    def __call__(self, dataset_run_id: str) -> dict[str, Any]:
        residual: list[str] = []
        try:
            containers = self._containers()
            expected_commit = self.snapshot["commit"]
            commits = {
                entry.removeprefix("SOURCE_COMMIT=")
                for container in containers
                for entry in container.get("Config", {}).get("Env", []) or []
                if entry.startswith("SOURCE_COMMIT=")
            }
            if commits != {expected_commit}:
                residual.append("source-commit-drift")

            live_images = {
                container.get("Config", {}).get("Labels", {}).get(
                    "com.docker.compose.service"
                ): container.get("Image")
                for container in containers
            }
            expected_images = {
                item["imageId"]: item["digest"]
                for item in self.snapshot["compose"]["images"]
            }
            for service, digest in expected_images.items():
                if live_images.get(service) != digest:
                    residual.append(f"image-drift:{service}")

            for url in self.health_urls:
                status, _ = self.http_reader(url)
                if status != 200:
                    residual.append(f"health:{url}")
            status, proxy = self.http_reader(
                self.toxiproxy_url + "/proxies/inventory-downstream"
            )
            if status != 200:
                residual.append("toxiproxy-unavailable")
            elif any(
                str(toxic.get("name", "")).startswith("inventory-latency-")
                for toxic in proxy.get("toxics", [])
                if isinstance(toxic, Mapping)
            ):
                residual.append("toxiproxy-residual-toxic")
        except (KeyError, TypeError, ValueError, json.JSONDecodeError, OSError) as exc:
            raise ContractError(
                ReleaseErrorCode.QUALITY_ENVIRONMENT_RECOVERY_FAILED.value,
                dataset_run_id,
            ) from exc
        return {
            "recovered": not residual,
            "residualFaults": residual,
            "environmentDigest": self.snapshot["snapshotDigest"],
        }

    def _containers(self) -> list[dict[str, Any]]:
        listed = self.command_runner(
            [
                "docker",
                "ps",
                "-a",
                "--filter",
                f"label=com.docker.compose.project={self.compose_project}",
                "--format",
                "{{.ID}}",
            ],
            capture_output=True,
            text=True,
            check=False,
            timeout=30,
        )
        ids = listed.stdout.split() if listed.returncode == 0 else []
        if not ids:
            raise OSError("compose containers unavailable")
        inspected = self.command_runner(
            ["docker", "inspect", *ids],
            capture_output=True,
            text=True,
            check=False,
            timeout=30,
        )
        values = json.loads(inspected.stdout) if inspected.returncode == 0 else []
        if not isinstance(values, list) or not values:
            raise OSError("compose inspect unavailable")
        return values


def _http_json(url: str) -> tuple[int, Mapping[str, Any]]:
    request = urllib.request.Request(url, method="GET", headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            content = response.read()
            value = json.loads(content) if content else {}
            return response.status, value if isinstance(value, Mapping) else {}
    except urllib.error.HTTPError as exc:
        return exc.code, {}
    except (urllib.error.URLError, TimeoutError):
        return 0, {}
