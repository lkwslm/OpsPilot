# Fault Lab

Fault Lab 提供阶段 07 的可恢复故障场景执行、隔离数据集生成和 Ground Truth 导出能力。

本地运行时必须同时加载基础 Compose 与 Phase 7 overlay，确保 Docker 控制权、Ground Truth 挂载和专用数据库凭证不会进入基础运行拓扑：

```powershell
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.phase7.yml --profile phase7 run --rm fault-lab list
```

运行前需在 `.tmp/secrets/` 准备 `fault-lab-db-password.txt` 与 `evaluation-db-password.txt`。Agent 只读访问 `deployment/agent-input/current/`，不会获得 Ground Truth、执行日志或 Docker socket。
