## ADDED Requirements

### Requirement: 关联标识跨全部边界传播
REST、SSE、A2A、Agent runtime、Tool、Provider、Artifact、审计和日志 MUST 传播受控的 `requestId/traceId/runId/stepId/a2aTaskId/invocationId`；每个边界只能生成缺失且属于自身作用域的标识，不得接受调用方覆盖权威 Run/Task/invocation 归属。

#### Scenario: 北向请求贯穿 RCA 链路
- **GIVEN** 一个成功完成的 Incident 调查请求
- **WHEN** 查询 REST、A2A、Tool、Provider、Evidence 和 RCA 审计记录
- **THEN** 可通过共享关联标识重建单一调用链，且每一跳的 runId/stepId/Task/invocation 归属一致

### Requirement: 错误与状态共享稳定关联信息
REST `ErrorResponse`、SSE error event、A2A status、`ChainFailure`、审计和结构化日志 MUST 使用一致的稳定错误分类及可用关联 ID，同时 MUST NOT 回显内部 cause、堆栈、Prompt、原始正文、凭证或未授权标识。

#### Scenario: 下游错误包含 Secret
- **GIVEN** Provider 或 Code Source 原始错误包含 Token、URL query 和内部堆栈
- **WHEN** 错误跨 A2A 传播到 REST/SSE
- **THEN** 每层保留相同关联链和稳定错误分类，但所有敏感值与内部堆栈均被移除

### Requirement: 大日志只通过受权 Artifact 引用
超过内联边界或包含受控原文的日志 MUST 先保存为受访问级别、保留策略和 SHA-256 保护的 Artifact，线路和审计只传 `logArtifactId` 与摘要；未授权主体 MUST NOT 解析该引用。

#### Scenario: 未授权日志引用访问
- **GIVEN** principal 拥有 Run 状态读取权限但没有目标日志 Artifact 权限
- **WHEN** 通过 API 或审计引用请求日志正文
- **THEN** 正文访问被拒绝且不泄露路径、URI 或存在性，状态响应仍只包含受控摘要

### Requirement: 审计链覆盖成功与所有失败路径
系统 MUST 为授权、审批、预算、A2A 委派、Tool/Provider 调用、Artifact 校验、Evidence 写入、analysis seal、RCA 和 API 幂等记录成功、拒绝、失败与取消审计；审计 MUST 包含主体、动作指纹、有效权限摘要、关联 ID、时间和结果，不得含 Secret 或隐藏推理。

#### Scenario: Artifact 第四层校验失败可追踪
- **GIVEN** 一个 Task/Run 归属错误的 Artifact
- **WHEN** receiver 在归属校验层拒绝它
- **THEN** 审计可从北向 requestId 追到目标 a2aTaskId 和校验层，领域表无写入且记录不含 Artifact 原始敏感正文

### Requirement: 跨主体关联查询 fail closed
关联审计查询 MUST 在每一跳重新校验 principal、Incident/Run、Artifact 和资源 scope，不能仅凭 traceId、a2aTaskId 或 invocationId 授权；不存在与无权限 MUST 使用不泄漏存在性的受控响应。

#### Scenario: 猜测其他主体 traceId
- **GIVEN** principal A 猜测到 principal B 的 traceId
- **WHEN** A 查询审计链
- **THEN** 系统不返回 B 的 Task、Tool、Evidence 或 RCA 信息，也不确认 traceId 是否存在

### Requirement: 关联完整性可自动验证
阶段验收 MUST 生成至少一条 `REST → Supervisor → 专业 A2A → Tool/Provider → Evidence → RCA → SSE` 的关联图和机器可读断言，验证必需 ID、父子关系、脱敏结果与 `logArtifactId` 权限完整。

#### Scenario: 完整纵切关联门禁
- **GIVEN** 六进程真实纵切已完成
- **WHEN** 运行关联完整性和 Secret 扫描
- **THEN** 所有必需节点与边均可回溯、没有孤立副作用或敏感值，缺任一关联字段使阶段门禁失败
