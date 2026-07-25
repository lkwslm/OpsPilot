# Purpose

定义 Source Adapter 的显式注册、能力快照、Secret 边界和确定性多 Source 选择规则。

## Requirements

### Requirement: Adapter 显式注册并冻结能力快照
`SourceAdapterRegistry` MUST 由 composition root 显式装配 Adapter，使用稳定 Adapter ID/version，启动时拒绝重复 ID、执行共享合同探针并生成 capability snapshot；探针完成后 Adapter 实现集合 MUST 冻结，MUST NOT 使用 classpath 自动发现、通用 Extension Host 或运行时热替换。

#### Scenario: 重复 Adapter ID 阻止启动
- **GIVEN** composition root 注册两个具有相同稳定 ID 的 Adapter 实现
- **WHEN** Registry 初始化并准备生成 capability snapshot
- **THEN** 启动失败并报告重复 ID，Registry 不发布部分能力快照

#### Scenario: 启动探针失败
- **GIVEN** 一个已配置 Adapter 无法通过共享合同探针
- **WHEN** Registry 完成启动检查
- **THEN** 对应能力不被标记为 READY，失败原因可审计且 required Source 不会被静默替代

### Requirement: Source 实例配置不泄漏 Secret
Source Registry MUST 保存 `sourceId/sourceKind/enabled/capabilities/connectionRef/scope/adapterId/adapterVersion/timeout/concurrency/dataClassification/health` 等受控字段，并通过 Secret Resolver 解析 `connectionRef`；URL、Token、密码和解析后的凭证 MUST NOT 写入 Observation、日志或模型上下文。

#### Scenario: 使用 connectionRef 调用 Source
- **GIVEN** 一个 Source 实例配置包含有效 `connectionRef` 且调用方有权访问对应 Secret
- **WHEN** Adapter 执行查询并保存审计与 Observation
- **THEN** 凭证只在执行边界短暂解析，持久结果和日志仅记录 `connectionRef` 而不包含 Secret 或任意 URL

### Requirement: 多 Source 关系必须显式声明
同一 Resource/Signal 绑定多个 Source 时，每个关系 MUST 声明 `PRIMARY`、`CORROBORATING` 或 `FALLBACK_DISABLED`，关系组合和优先级 MUST 通过配置校验；未声明或互相冲突的配置 MUST 被拒绝。

#### Scenario: 多 Source 未声明角色
- **GIVEN** 同一 Resource/Signal 配置了两个 READY Source 但未声明关系角色
- **WHEN** 配置进入 Registry 或选择器
- **THEN** 配置被拒绝且不会由列表顺序、发现顺序或模型选择其中一个 Source

### Requirement: Source 选择确定且禁止静默换源
Source 选择 MUST 仅依据当前 Target/Run 内的 Resource、信号类型、版本化查询模板、场景 required source、Source READY 状态和配置优先级；模型输入 MUST NOT 指定 URL。技术失败后 MUST NOT 自动切换 Source，显式多源交叉验证 MUST 分别保存每个 Source 的结果或失败。

#### Scenario: PRIMARY Source 查询超时
- **GIVEN** 选择器确定一个 PRIMARY Source，另有一个 READY 的 CORROBORATING Source
- **WHEN** PRIMARY Source 调用超时且请求未显式要求多源查询
- **THEN** 系统记录关联 `ChainFailure` 并结束该调用，不自动查询 CORROBORATING Source，也不返回 `EMPTY`

#### Scenario: required Source 缺失
- **GIVEN** 场景声明一个 required source 但当前 Target 未配置或未 READY
- **WHEN** Tool 请求进行 Source 选择
- **THEN** 返回稳定缺失或失败结果并记录审计，不使用其他产品或模型提供的地址替代

### Requirement: Source 配置变更与实现变更分离
系统 MUST 允许经校验和审计受控增删 Source 实例配置；Adapter ID/version 实现集合发生变化时 MUST 要求进程重启并生成新的 capability snapshot，冻结后的 Registry MUST 拒绝就地修改。

#### Scenario: 冻结后尝试热替换 Adapter
- **GIVEN** Registry 已发布 capability snapshot 并冻结
- **WHEN** 管理操作尝试在不重启的情况下增加或替换 Adapter 实现
- **THEN** 操作被拒绝且当前 snapshot 不变；重启并通过探针后才可发布新的 snapshot
