# six-process-compose-topology Specification

## Purpose
TBD - Defines the Phase 0 contract and acceptance criteria for six-process-compose-topology.

## Requirements

### Requirement: 冻结的六进程拓扑
Compose MUST（必须）创建一个监听 8080 的产品/Supervisor 进程和五个监听 8081–8085 的专业 Agent 进程；专业 Agent 端口只能在 Compose 内网暴露，不得绑定宿主机。

#### Scenario: Compose 拓扑符合冻结布局
- **GIVEN** `deployment/docker-compose.yml`
- **WHEN** 执行 `docker compose config` 并检查端口绑定
- **THEN** 六个服务配置有效，只有产品端点绑定宿主机，五个专业端点仅在内部网络可达

### Requirement: 进程身份和权限隔离
每个进程 MUST（必须）使用不同的 `AGENT_ID`、服务 Token、数据库角色和 A2A URL；Token 只能调用对应 skill。

#### Scenario: 跨 Agent Token 被拒绝
- **GIVEN** 一个专业 Agent 的服务 Token
- **WHEN** 用它调用另一个 Agent 的未授权 skill 或数据库角色
- **THEN** 请求被拒绝且不会产生 Task 或领域写入

### Requirement: 输入卷最小暴露
Agent input MUST（必须）只读挂载，不得挂载 Ground Truth、execution 敏感卷或 Docker socket。

#### Scenario: Agent 容器不可见敏感资产
- **GIVEN** 已启动的专业 Agent 容器
- **WHEN** 检查挂载和文件访问权限
- **THEN** input 为只读，Ground Truth、敏感 execution 资产和 Docker socket 均不可见

### Requirement: 启动依赖与健康语义
Migration MUST（必须）是硬启动依赖；一次性 retrieval probe 是 Phase 0/部署资格门禁而不是 Server/Agent 完成型启动依赖。Server/Agent 必须运行等价探针：确定性不兼容时非零退出，配置合法但模型或 Card/endpoint 暂不可达时保持 liveness UP、readiness DOWN，并在恢复后重新探针。

#### Scenario: 临时端点故障只阻止 readiness
- **GIVEN** 配置和身份合法但真实模型或 A2A 端点暂时不可达
- **WHEN** 启动 Server/Agent 并查询健康端点
- **THEN** 进程保持运行且 liveness 为 UP、readiness 为 DOWN，不接收新任务，并在端点恢复后重新探针转为 ready

#### Scenario: 确定性不兼容使进程退出
- **GIVEN** 模型 identity、revision、维度、协议版本或必需能力与版本锁确定性不一致
- **WHEN** Server/Agent 执行启动探针
- **THEN** 进程以非零退出码结束并输出脱敏的具体不兼容原因
