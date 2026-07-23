## MODIFIED Requirements

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

## ADDED Requirements

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
