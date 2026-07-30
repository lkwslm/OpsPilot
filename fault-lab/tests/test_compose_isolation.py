from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
AGENT_SERVICES = (
    "opspilot-server",
    "evidence-agent",
    "code-agent",
    "knowledge-agent",
    "diagnosis-agent",
    "remediation-agent",
)
RESTRICTED_RUNTIME_MARKERS = (
    "/datasets/ground-truth",
    "/datasets/execution",
    "evaluation-db-password",
    "fault-lab-db-password",
)


def volumes(service: dict) -> str:
    return str(service.get("volumes", []))


def test_only_fault_lab_has_docker_control_and_restricted_dataset_paths() -> None:
    compose = yaml.safe_load((ROOT / "deployment" / "docker-compose.yml").read_text(encoding="utf-8"))
    services = compose["services"]
    for name, service in services.items():
        mounts = volumes(service)
        if name == "fault-lab":
            assert "docker.sock" in mounts
            assert "/datasets" in mounts
            assert "/exports/ground-truth" in mounts
        else:
            assert "docker.sock" not in mounts
            assert "/datasets/execution" not in mounts
            if name != "evaluation":
                assert "/datasets/ground-truth" not in mounts
    assert ":ro" in volumes(services["evaluation"])
    assert services["fault-lab"]["environment"]["DB_USERNAME"] == "fault_lab_login"
    assert services["evaluation"]["environment"]["DB_USERNAME"] == "evaluation_login"
    assert services["fault-lab"]["secrets"] == ["fault-lab-db-password"]
    assert services["evaluation"]["secrets"] == ["evaluation-db-password"]


def test_agent_input_is_read_only_for_all_agent_services() -> None:
    compose = yaml.safe_load((ROOT / "deployment" / "docker-compose.yml").read_text(encoding="utf-8"))
    for name in AGENT_SERVICES:
        assert "read_only" in volumes(compose["services"][name])


def test_agent_ground_truth_access_matrix_is_fail_closed() -> None:
    compose = yaml.safe_load((ROOT / "deployment" / "docker-compose.yml").read_text(encoding="utf-8"))
    services = compose["services"]

    # 文件、环境变量和凭证：Agent 仅能看到只读 input，不能获得评测或故障实验身份。
    for name in AGENT_SERVICES:
        service = services[name]
        visible_configuration = str({
            "environment": service.get("environment", {}),
            "secrets": service.get("secrets", []),
            "volumes": service.get("volumes", []),
        }).lower()
        assert "/datasets/input" in visible_configuration
        for marker in RESTRICTED_RUNTIME_MARKERS:
            assert marker not in visible_configuration

    evaluation = services["evaluation"]
    assert evaluation["environment"]["DB_USERNAME"] == "evaluation_login"
    assert evaluation["secrets"] == ["evaluation-db-password"]
    assert ":ro" in volumes(evaluation)
    assert "/datasets/ground-truth" in volumes(evaluation)

    # 数据库：Agent 角色显式撤销 opspilot_eval，Evaluation 角色只读 Ground Truth。
    migration = (ROOT / "opspilot-adapters" / "persistence-postgres" / "src" / "main"
                 / "resources" / "db" / "migration" / "V7__enforce_indexes_roles_and_lifecycle.sql").read_text(
                     encoding="utf-8")
    assert "REVOKE ALL ON ALL TABLES IN SCHEMA opspilot_eval FROM PUBLIC, opspilot_app_role, professional_agent_role" in migration
    assert "diagnosis_agent_role, remediation_agent_role" in migration
    assert "GRANT SELECT ON ALL TABLES IN SCHEMA opspilot_eval TO evaluation_role" in migration

    # API：产品合同不暴露 Ground Truth 或 Evaluation 服务端点。
    openapi = yaml.safe_load((ROOT / "docs" / "design" / "contracts" / "openapi"
                             / "opspilot-v1.yaml").read_text(encoding="utf-8"))
    exposed_paths = "\n".join(openapi["paths"]).lower()
    assert "ground-truth" not in exposed_paths
    assert "ground_truth" not in exposed_paths
    assert "evaluation" not in exposed_paths

    # 日志与 Prompt：运行时资源不得嵌入受限路径或凭证；系统策略显式禁止索取或泄露标准答案。
    runtime_root = ROOT / "opspilot-agent-runtime-agentscope" / "src" / "main"
    runtime_text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in runtime_root.rglob("*")
        if path.is_file()
    ).lower()
    for marker in RESTRICTED_RUNTIME_MARKERS:
        assert marker not in runtime_text
    policy = (runtime_root / "resources" / "agent-profiles" / "prompts"
              / "opspilot-system-policy-v1.md").read_text(encoding="utf-8").lower()
    assert "never request or reveal secrets, ground truth" in policy
