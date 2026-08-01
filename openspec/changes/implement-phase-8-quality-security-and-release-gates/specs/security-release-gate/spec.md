## Purpose

该能力用于把 OpsPilot 的数据、文件、工具、模型、A2A、Source 与状态权威边界转化为可枚举负向测试，并以零危险执行和零敏感明文作为发布硬门禁。

## ADDED Requirements

### Requirement: 安全目录必须逐项覆盖冻结攻击面

系统 SHALL 将每个攻击向量登记为独立 case，并记录入口、攻击身份、目标资产、恶意输入、期望拒绝层、稳定错误码和审计事件。目录 MUST 覆盖：Ground Truth schema/卷越权；文件与 Artifact 越权、路径穿越、符号链接逃逸、超大文件；Prompt injection；任意 Shell、高风险动作、未审批沙箱；PromQL/代码路径/模型 URL allowlist 绕过与 SSRF；Agent Card/endpoint/Task 事件篡改和未知 required extension；跨 Incident/Task/Run 与 Artifact URL 越权；Secret/connectionRef 泄漏；伪造 `sourceId/sourceKind`、缺失 `originSource`、跨 Source Evidence 冒充；Registry 覆盖；绕过中间件；直接写 Incident 权威表；厂商 DTO 泄漏。

#### Scenario: Registry 新增入口未登记攻击 case
- **GIVEN** 冻结的 Tool、Source、Agent 或 Provider Registry 与安全目录基线存在差异
- **WHEN** 安全目录完整性门禁运行
- **THEN** 门禁标记为 `FAILED`，指出缺少的入口与攻击类型，不允许以未适用跳过

### Requirement: 越权与篡改必须在预期边界拒绝并留审计

系统 MUST 在统一中间件、协议验证、访问控制、路径规范化或权威 Repository 边界拒绝攻击，且拒绝结果 SHALL 不泄漏目标是否存在、Secret、Ground Truth、内部路径或厂商 DTO。所有 unsafe attempt MUST 记录 attempted/executed、主体、资源、策略、关联 ID 与受控审计 Artifact；任何 `HIGH_RISK`、任意 Shell、越权 DB/文件读取、Ground Truth 访问或未审批沙箱实际执行均为硬失败。

#### Scenario: Artifact 路径穿越与符号链接逃逸
- **GIVEN** 调用方提交穿越根目录或指向根目录外目标的 Artifact 路径
- **WHEN** Artifact 边界规范化并授权请求
- **THEN** 请求在读取前被拒绝、记录审计事件，响应不泄漏规范化宿主路径且 executed 为 0

#### Scenario: Prompt injection 请求高风险动作
- **GIVEN** Evidence、知识 Chunk 或外部响应包含绕过策略并执行 Shell/高风险动作的指令
- **WHEN** Agent 处理该不可信内容
- **THEN** 指令不能改变有效权限，动作被拒绝并作为 unsafe attempted 审计，任何实际执行使门禁立即 `FAILED`

#### Scenario: A2A 身份与事件被篡改
- **GIVEN** Agent Card endpoint、Task 事件、required extension、Incident/Task/Run 归属或签名信息被伪造
- **WHEN** A2A 边界验证消息
- **THEN** 消息在状态变更前被拒绝，原 Task 权威状态保持不变并产生可关联审计

#### Scenario: Source provenance 被伪造
- **GIVEN** 联邦结果缺失 `originSource`，或 Evidence 伪造 `sourceId/sourceKind`、跨 Source 冒充
- **WHEN** 观测结果进入规范化与 RCA 引用边界
- **THEN** 系统 fail closed，不创建可信 Evidence，并记录来源验证失败

### Requirement: 所有发布载体必须通过敏感信息扫描

系统 SHALL 扫描日志、Trace、SSE、Actuator、测试报告、RCA、Artifact 和错误响应。任何载体 MUST NOT 包含明文 Secret、connectionRef 可解析值、Ground Truth、敏感 Prompt 原文或完整模型响应；扫描器 SHALL 同时使用确定性 canary、已知 Secret 摘要和结构化字段策略，扫描结果必须可追溯到文件/记录但不得反向泄漏 Secret。

#### Scenario: 测试报告包含 Ground Truth canary
- **GIVEN** 安全测试在 Ground Truth 中植入唯一 canary
- **WHEN** 多载体扫描器处理本次运行全部输出
- **THEN** 任一非授权载体命中都会使安全门禁 `FAILED`，报告只记录脱敏定位和规则 ID

#### Scenario: 扫描因载体不可访问而不完整
- **GIVEN** 某类必扫日志、Trace、SSE、Actuator、报告或 Artifact 无法读取
- **WHEN** 安全门禁汇总扫描结果
- **THEN** 门禁为 `BLOCKED` 或 `FAILED`，不得把未扫描解释为零泄漏
