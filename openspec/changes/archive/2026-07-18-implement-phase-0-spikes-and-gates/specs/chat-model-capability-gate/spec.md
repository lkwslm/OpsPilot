## ADDED Requirements

### Requirement: 逻辑 Profile 解析
系统 MUST（必须）解析默认配置和六角色稀疏覆盖，得到每个逻辑 LLM Profile 的实际模型 identity、Secret ref、上下文上限、结构化输出、Tool calling、流式要求和配额，并对相同实际模型去重探测。

#### Scenario: 六角色配置完全展开
- **GIVEN** 默认模型配置与六角色稀疏覆盖
- **WHEN** 运行 Profile 解析器
- **THEN** 每个逻辑 Profile 都得到完整且可追溯的最终能力要求，不读取或输出 Secret 值

### Requirement: 真实能力逐项探测
对去重后的每个实际模型 MUST（必须）执行真实 Provider 最小调用；结构化输出、Tool calling 和所需流式能力必须分别探测，不得根据 OpenAI-Compatible 名称或静态声明推断通过。

#### Scenario: 所需能力经真实调用验证
- **GIVEN** Provider 凭证、配额和一个声明多项必需能力的逻辑 Profile
- **WHEN** 执行模型能力探针
- **THEN** 每项必需能力都有独立真实调用结果和实际模型 identity，任何未探测能力保持未通过

### Requirement: 脱敏验收记录
能力记录 MUST（必须）包含模型、Provider、配置版本、探针请求类型、UTC 时间、Usage/配额和脱敏错误，不得保存 Key、完整 Prompt 或完整响应。

#### Scenario: 验收记录不泄漏敏感信息
- **GIVEN** 成功和失败的真实模型调用结果
- **WHEN** 生成版本化验收记录并执行泄漏检查
- **THEN** 记录包含门禁所需元数据且不包含 Secret、完整 Prompt 或完整响应

### Requirement: 未验证 Profile 阻止完成
模型为空、Key 缺失、上下文上限未知、必需能力不支持、配额不足或任一逻辑 Profile 未验证时，Phase 0 MUST（必须）fail-fast。

#### Scenario: 任一能力缺口失败
- **GIVEN** 七类逻辑 Profile 中至少一个存在配置或能力缺口
- **WHEN** 汇总 Chat 模型门禁
- **THEN** 门禁以非零退出码失败并列出未验证 Profile 与具体缺口
