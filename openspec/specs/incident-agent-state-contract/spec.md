# incident-agent-state-contract Specification

## Purpose

定义 Incident Agent 控制平面快照的版本、边界、迁移、引用完整性和状态所有权合同，确保恢复过程可验证、可隔离且不会泄露受限内容。

## Requirements

### Requirement: 版本化控制平面快照
`IncidentAgentState` MUST 以版本化 JSON 合同保存身份、进度、steps/attempts、Evidence/Hypothesis 引用、预算、循环治理、缺失、失败、报告和取消字段，且字段含义与权威状态所有者一致；PostgreSQL 表列 version MUST 与 JSON 内 version 一致，保存与加载 MUST 校验所有必需引用存在、类型匹配且属于同一 Run。

#### Scenario: 受支持版本往返
- **GIVEN** 任一受支持 Schema 版本、表列 version 与 JSON version 一致且引用合法的完整状态
- **WHEN** 状态经 PostgreSQL Repository 保存后再加载
- **THEN** 所有合同字段和值保持等价、两个 version 保持一致、引用归属通过校验且状态所有权不改变

#### Scenario: 表列与 JSON 版本不一致
- **GIVEN** 候选快照的表列 version 与 JSON 内 version 不同
- **WHEN** Repository 保存或加载该状态
- **THEN** 操作以稳定状态一致性错误失败，不切换运行时状态或使用文件/内存 Store 回退

### Requirement: 快照大小与集合有界
字符串、列表、steps、指纹、warnings 和最终序列化大小 MUST 使用预算或 Profile 给出的上限，任何写入不得使快照无界增长。

#### Scenario: 超出列表或序列化上限
- **GIVEN** 已达到对应 Profile 上限的快照
- **WHEN** 新字段或集合元素将使其越界
- **THEN** 更新以稳定边界错误被拒绝且原快照保持不变

### Requirement: 显式 Schema 迁移
状态加载 MUST 只通过声明支持的 source/target 版本迁移链；未知版本 MUST 返回 `STATE_SCHEMA_UNSUPPORTED`，不得猜测、丢弃字段或继续运行。

#### Scenario: 加载未知 Schema 版本
- **GIVEN** 未在迁移链声明的 `schemaVersion`
- **WHEN** 恢复 Incident Run
- **THEN** 返回 `STATE_SCHEMA_UNSUPPORTED` 并停止 Run，不执行 Tool、Model 或 A2A 调用

### Requirement: 引用完整性与 Run 隔离
加载状态时 MUST 验证必需引用存在、属于同一 Run 且类型匹配；缺失、跨 Run 或类型错误 MUST 返回 `STATE_REFERENCE_INVALID` 并停止 Run。

#### Scenario: Evidence 引用跨 Run
- **GIVEN** 快照引用另一个 Run 的 Evidence ID
- **WHEN** 引用校验器加载快照
- **THEN** 返回 `STATE_REFERENCE_INVALID`，不丢弃引用也不尝试猜测替代项

### Requirement: 禁止快照内容
快照 MUST NOT 保存日志/Trace/代码/知识正文、完整 Evidence、Prompt/Response、隐藏推理、Message history、Tool 原始输出、Secret、Ground Truth 或大 Artifact，只能保存受控摘要、身份与引用。

#### Scenario: 禁止内容扫描
- **GIVEN** 候选快照包含任一禁止正文或 Secret 模式
- **WHEN** 保存前执行 Schema 和内容检查
- **THEN** 保存 fail closed，禁止内容不进入任何状态存储

### Requirement: 三个状态平面所有权分离
`IncidentAgentState`、AgentScope state 和 A2A Task state MUST 通过不同 Port 和所有者管理，任何一方不得覆盖另一方的 JSON 或版本。

#### Scenario: AgentScope 恢复不覆盖 Incident 状态
- **GIVEN** AgentScope state 与 IncidentAgentState 都存在 checkpoint
- **WHEN** 运行时恢复 AgentScope 会话
- **THEN** 只更新 AgentScope 所有字段，Incident 和 A2A 状态保持原版本

### Requirement: PostgreSQL compareAndSet 不得覆盖并发状态
`IncidentAgentStateRepository.compareAndSet` MUST 以预期 version 原子更新状态；CAS 冲突后调用方 MUST 重读最新状态并重新判断迁移，MUST NOT 覆盖写或沿用旧决策。

#### Scenario: 并发 CAS 更新
- **GIVEN** 两个执行者读取相同的 IncidentAgentState version
- **WHEN** 第一个提交成功而第二个执行 `compareAndSet`
- **THEN** 第二个更新影响零行并得到稳定 CAS 冲突，重读前不得执行依赖旧状态的后续副作用

### Requirement: AgentScope state 的持久化身份隔离
AgentScope state MUST 按 `(server_agent_id,user_id,session_id)` 隔离；continuation MUST 复用原会话，新 attempt MUST 使用新会话。持久化失败 MUST 阻止状态切换，且 MUST NOT 回退到内存或文件 Store。

#### Scenario: 新 attempt 与 continuation 会话选择
- **GIVEN** 一个已有 AgentScope checkpoint 的 attempt
- **WHEN** 分别恢复 continuation 和创建新 attempt
- **THEN** continuation 使用原 `session_id`，新 attempt 使用新的 `session_id`，二者状态行不能互相覆盖
