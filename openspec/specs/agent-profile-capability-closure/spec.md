## Purpose

本规格定义 Phase 6 的 agent-profile-capability-closure 能力边界、约束、可验证场景，以及阶段验收所需的稳定行为合同。

## Requirements

### Requirement: 六个版本化 AgentProfile
系统 MUST 按 `agent-profile.schema.json` 提供且仅提供 Supervisor、EvidenceCollector、CodeAnalysis、Knowledge、Diagnosis、Remediation 六个 Profile，并为每个 Profile 冻结 role/skill、版本化 Prompt、输入/输出 Schema、Tool/A2A 白名单、预算、完成条件和沙箱策略。

#### Scenario: 六个 Profile 通过机器合同
- **GIVEN** 六个待注册 Profile
- **WHEN** 使用 JSON Schema 和 role/skill 配对规则校验
- **THEN** 六个 Profile 均通过且 profileId/profileVersion 唯一，缺少、重复或 role/skill 不匹配时启动失败

### Requirement: 有效权限取严格交集
每次执行的有效权限 MUST 等于“平台安全基线 ∩ 服务身份权限 ∩ AgentProfile ∩ 单次任务约束 ∩ 有效人工审批”；任一层缺失或拒绝 MUST 缩小或清空权限，任何层 MUST NOT 扩大其他层。

#### Scenario: 审批不能突破平台基线
- **GIVEN** 一个已审批但平台基线禁止的 HIGH_RISK 或代码写入动作
- **WHEN** 计算有效权限
- **THEN** 动作仍被拒绝且 Tool/Sandbox 执行次数为零，审计记录各层决策摘要

### Requirement: 注册时验证能力闭包并冻结
Profile Registry MUST 在启动时验证引用的 Model capability、Tool contract major、A2A skill、Schema、Sandbox runner 和安全策略均已注册且与服务身份一致，验证成功后 MUST 冻结；缺失、不兼容、越权或冻结后修改 MUST 失败。

#### Scenario: 缺少 required skill 阻止就绪
- **GIVEN** Diagnosis Profile 声明的 A2A skill 未在其服务 Card/身份中注册
- **WHEN** 计算能力闭包并冻结 Registry
- **THEN** 注册失败、服务 readiness 为 DOWN，Profile 不可用于创建 Task

### Requirement: Evidence-only 与敏感配置边界
所有 Profile MUST 设置 `factsFromEvidenceOnly=true`，只保存逻辑 `modelProfileRef`；Profile MUST NOT 包含 Provider URL、API Key、Secret 值，也 MUST NOT 将 Ground Truth、Secret、任意命令或代码修改配置为允许。

#### Scenario: 敏感字段或基线放宽被拒绝
- **GIVEN** 一个包含 Provider URL/API Key 或把 `codeMutation` 设为允许的 Profile
- **WHEN** 执行 Schema、敏感字段和安全基线校验
- **THEN** Profile 在注册前被拒绝，错误仅指出字段路径且不回显敏感值

### Requirement: 六角色动作受白名单限制
运行时 MUST 仅向 Agent 暴露其 Profile、服务身份和单次任务共同允许的 Tool 与 A2A skill；模型生成的 Tool 名、URL、skill 或参数 MUST 经过 Schema、授权、审批、预算和资源 scope 校验。

#### Scenario: 模型请求未授权能力
- **GIVEN** Knowledge Agent 生成一个不在 Profile 白名单中的 CodeSearchTool 或远端 URL
- **WHEN** 请求进入受控调用链
- **THEN** 请求返回 `DENIED`，不得动态注册或调用该能力，并产生脱敏审计事件

### Requirement: Profile 版本进入运行快照
创建 Run/Task 时 MUST 保存去密钥的 Profile ID、版本、Prompt 版本、模型逻辑引用、能力闭包 digest 和有效任务约束，运行中配置更新 MUST NOT 改变已有 Run/Task 的解释。

#### Scenario: 热更新不污染已有运行
- **GIVEN** 一个使用 Profile v1 的进行中 Task
- **WHEN** 部署 Profile v2 并创建新 Task
- **THEN** 原 Task 继续使用 v1 快照，新 Task 使用 v2，二者审计可重放且不含 Secret
