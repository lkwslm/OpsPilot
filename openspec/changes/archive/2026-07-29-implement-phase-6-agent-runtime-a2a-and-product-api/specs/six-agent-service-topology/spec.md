## ADDED Requirements

### Requirement: 六个 Agent 运行于独立进程边界
系统 MUST 运行 8080 Supervisor/产品服务和 8081–8085 EvidenceCollector、CodeAnalysis、Knowledge、Diagnosis、Remediation 五个专业服务；专业 Agent MUST NOT 内嵌进 `opspilot-server`，Supervisor 到专业 Agent MUST 只经 Compose 内网 HTTP+JSON 调用。

#### Scenario: 禁用网络路径后调用失败
- **GIVEN** 六进程 Compose 已启动且一次专业委派可完成
- **WHEN** 禁用 Supervisor 到目标专业服务的网络路径
- **THEN** 委派以可恢复网络失败结束，不存在进程内 Bean 调用或本地快捷路径

### Requirement: 端口和 origin 暴露最小化
产品端口 MUST 只绑定宿主 `127.0.0.1:8080`，8081–8085 MUST 只在 Compose 内网可达；每个服务 MUST 在自己的 origin 暴露唯一 `/.well-known/agent-card.json`，不得由 8080 代理成同一 origin 下的六张 Card。

#### Scenario: 端口扫描和六 origin Card
- **GIVEN** 冻结 Compose 拓扑已启动
- **WHEN** 从宿主和 Compose 内网分别扫描端口并读取 Card
- **THEN** 宿主只可访问 127.0.0.1:8080，内网可访问五个专业端口，六个 origin 各返回自身唯一 Card

### Requirement: 服务身份、Token 和数据库角色隔离
六个进程 MUST 使用独立服务身份、仅包含自身或调用所需 skill scope 的不同 Token、独立 A2A base URL 和最小数据库角色；专业身份 MUST 不具备 Supervisor 领域表写权限。

#### Scenario: 跨 skill Token 被拒绝
- **GIVEN** EvidenceCollector 的 Token 和 CodeAnalysis skill endpoint
- **WHEN** 使用该 Token 调用 CodeAnalysis skill
- **THEN** A2A Server 在创建 Task 前拒绝请求，数据库和 runtime 无新增状态

### Requirement: Agent Directory 只读且摘要可核验
Agent Directory MUST 随镜像或部署清单发布，包含静态 Agent/skill/base URL/Card 期望和安全配置，并计算持久 digest；运行时和模型 MUST NOT 新增、删除或修改 endpoint，也不得从模型输出发现 URL。

#### Scenario: 模型返回未知 endpoint
- **GIVEN** 模型输出一个不在 Directory 的 A2A URL
- **WHEN** Supervisor 选择委派 endpoint
- **THEN** 未知 URL 被拒绝且不发起网络请求，审计记录 Directory digest 和稳定拒绝原因

### Requirement: 只选择 READY 且合同匹配的 endpoint
系统 MUST 维护独立于 TaskState 的 `AgentEndpointState`、探针时间、Card digest 和 capability snapshot；调度 MUST 只选择 `READY` 且 Agent 身份、skill、协议、媒体类型、required extension 与 Directory 匹配的 endpoint。

#### Scenario: Card digest 漂移自动摘流
- **GIVEN** 一个原 READY endpoint 返回与 Directory 不同的 Card digest
- **WHEN** endpoint probe 更新状态并执行新委派
- **THEN** endpoint 不再被选择、readiness 对应能力为 DOWN，已有 Task 仍通过 binding 对账而不改写目标

### Requirement: 启动校验完成后才接受流量
每个服务 MUST 按“机器合同/Profile/Directory 静态校验 → 身份与 Secret ref → Provider/Tool/skill 真实探针 → Card/Directory digest 对账 → Registry 冻结 → readiness”顺序启动；任一 required 步骤失败 MUST 阻止 readiness UP 和新 Task 创建。

#### Scenario: Registry 冻结前拒绝 Task
- **GIVEN** 服务已存活但尚未完成 capability probe 与 Registry 冻结
- **WHEN** 客户端发送新 A2A Task
- **THEN** 服务返回不可就绪错误且不创建 Task，liveness 仍可为 UP
