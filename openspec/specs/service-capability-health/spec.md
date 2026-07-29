## Purpose

本规格定义 Phase 6 的 service-capability-health 能力边界、约束、可验证场景，以及阶段验收所需的稳定行为合同。

## Requirements

### Requirement: 每个进程提供四类健康端点
六个进程 MUST 提供 liveness、readiness、models 和 capabilities 端点；响应 MUST 使用稳定 Schema、UTC 探针时间、配置/Directory/Card digest 和受控错误摘要，不得泄露 Secret、Provider URL 或 Token。

#### Scenario: 六进程健康合同一致
- **GIVEN** 六进程运行且使用不同角色配置
- **WHEN** 对每个进程调用四类健康端点
- **THEN** 所有响应通过共享 Schema，角色能力不同但字段语义一致且无敏感值

### Requirement: capability 仅报告 UP 或 DOWN
单项 capability MUST 只报告 `UP|DOWN`，MUST NOT 使用 `DEGRADED` 维持业务流量；required 能力为 DOWN 时 MUST 从 readiness 和 endpoint 选择中摘除相关服务。

#### Scenario: required skill 失效
- **GIVEN** 一个专业 A2A skill 的真实探针失败
- **WHEN** 更新 capability snapshot
- **THEN** skill 为 DOWN、相关服务 readiness 为 DOWN 且不再接收新 Task，不返回 DEGRADED

### Requirement: readiness 聚合全部必需能力
Supervisor/产品 readiness MUST 仅在数据库、Agent 状态持久化、LLM、Embedding、Rerank、Supervisor 运行时和五个专业 A2A skill 全部有效时为 UP；专业服务 MUST 聚合自身数据库/状态、模型、Tool/skill 与 Directory/Card 合同中的 required 能力。

#### Scenario: 逐依赖断开与恢复
- **GIVEN** 全部 required 能力初始为 UP
- **WHEN** 逐一断开并恢复数据库、LLM、Embedding、Rerank 和各专业 skill
- **THEN** 每次断开使对应 readiness 确定变为 DOWN，真实探针恢复后才回到 UP

### Requirement: liveness 与外部依赖可用性分离
外部 Provider、数据库或专业 endpoint 临时不可达 MUST NOT 自动使进程 liveness DOWN；只有进程内部无法继续提供健康/控制能力时 liveness 才能为 DOWN。

#### Scenario: Provider 临时超时
- **GIVEN** 进程事件循环和健康组件正常但 LLM endpoint 超时
- **WHEN** 执行健康聚合
- **THEN** liveness 保持 UP、相关 capability 与 readiness 为 DOWN，并记录可重试探针失败

### Requirement: KB_EMPTY 只表示数据状态
在真实 Embedding/Rerank/数据库能力探针均成功后，`KB_EMPTY` MUST 只表示当前知识 collection 没有可搜索数据，不得把模型能力标记为 DOWN，也不得用它跳过真实模型探针。

#### Scenario: 空知识库但能力有效
- **GIVEN** 真实模型和数据库探针通过且 collection 为空
- **WHEN** 计算 models、capabilities 和 readiness
- **THEN** 模型能力保持 UP，知识数据状态为 KB_EMPTY，readiness 按 required 能力而非文档数量判定

### Requirement: probe snapshot 与 Directory 选择一致
endpoint probe、Card digest、模型与 Tool/skill capability snapshot MUST 以同一配置版本生成并可审计；调度读取的 READY 状态 MUST 与最近有效 snapshot 对应，过期或版本不一致 MUST 视为 DOWN。

#### Scenario: 旧探针不能维持 READY
- **GIVEN** Directory 版本已更新但 endpoint 只有旧版本 capability snapshot
- **WHEN** 调度尝试选择该 endpoint
- **THEN** endpoint 被视为非 READY，直到新版本真实探针和 digest 对账完成
