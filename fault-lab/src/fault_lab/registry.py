from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .contracts import ContractError


@dataclass(frozen=True, order=True)
class ComponentKey:
    category: str
    name: str
    version: str


class ComponentRegistry:
    def __init__(self) -> None:
        self._components: dict[ComponentKey, Any] = {}
        self._frozen = False

    def register(self, category: str, name: str, version: str, component: Any) -> None:
        if self._frozen:
            raise ContractError("REGISTRY_FROZEN", f"{category}:{name}:{version}")
        key = ComponentKey(category, name, version)
        if key in self._components:
            raise ContractError("REGISTRY_DUPLICATE", str(key))
        self._components[key] = component

    def require(self, category: str, name: str, version: str = "1.0.0") -> Any:
        key = ComponentKey(category, name, version)
        if key not in self._components:
            raise ContractError("REGISTRY_COMPONENT_MISSING", str(key))
        return self._components[key]

    def freeze(self) -> None:
        self._frozen = True

    def manifest(self) -> list[dict[str, str]]:
        return [
            {"category": key.category, "name": key.name, "version": key.version}
            for key in sorted(self._components)
        ]
