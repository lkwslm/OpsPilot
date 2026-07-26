## ADDED Requirements

### Requirement: 三类 Provider Registry 独立装配并冻结
系统 MUST 分别提供 `ChatModelProviderRegistry`、`EmbeddingProviderRegistry` 和 `RerankProviderRegistry`，由 composition root 显式装配；同类稳定 ID 冲突、required Provider 缺失或协议/模型版本不兼容 MUST 使启动失败，探针完成后的 Registry MUST 拒绝任何新增、替换或删除。

#### Scenario: 重复稳定 ID 阻止启动
- **GIVEN** 同一 Registry 中存在两个声明相同稳定 ID 的 Adapter
- **WHEN** composition root 完成 Provider 注册
- **THEN** 进程以可定位到 Registry 和稳定 ID 的错误非零退出，且不按 Bean 或扫描顺序覆盖实现

#### Scenario: 冻结后不能改变实现
- **GIVEN** required Provider 已通过探针且 Registry 已冻结
- **WHEN** 任一调用方尝试注册、替换或删除 Provider
- **THEN** Registry 拒绝修改，既有 capability snapshot 与运行映射保持不变

### Requirement: 模型配置继承与 Run 级快照
系统 MUST 按“平台默认配置 → 六角色稀疏覆盖 → 单次任务白名单覆盖”解析有效配置；Provider、端点、Secret ref、模型、重试、并发和上下文能力 MUST 来自被引用的 model profile，单次任务不得覆盖非白名单字段。每个 Run MUST 固化不含 Secret 值的 effective model configuration 与配置版本，运行中配置更新 MUST NOT 改变该 Run。

#### Scenario: 稀疏覆盖确定性展开
- **GIVEN** 一个平台默认 profile、六角色稀疏覆盖和仅包含预算/输出长度白名单字段的任务覆盖
- **WHEN** 解析各角色有效配置并创建 Run
- **THEN** 每个字段按固定优先级得到唯一来源，Run 快照记录字段来源与版本且不包含解析后的 Secret

#### Scenario: 运行中配置更新隔离
- **GIVEN** 一个已固化模型与知识 revision 快照的运行中 Run
- **WHEN** 管理流程发布新的非敏感 model profile 版本
- **THEN** 既有 Run 继续使用原快照，新 Run 使用新版本，二者审计可分别重放

### Requirement: 静态校验与真实能力快照
系统 MUST 在启动时校验启用 profile 的 provider、base URL、model、Secret ref、上下文上限、数值边界和能力要求，并对去重后的真实模型逐项探测 identity、required capability 与版本。静态配置缺失或能力不兼容 MUST 使进程非零退出；配置完整但端点临时不可达时 MUST 保持 liveness UP、readiness DOWN，且不得注册未验证能力。

#### Scenario: 缺失字段精确失败
- **GIVEN** 启用的 profile 缺少模型名或上下文上限
- **WHEN** 执行启动静态校验
- **THEN** 进程非零退出并报告准确配置路径与应注入的变量名，不发起 Provider 调用

#### Scenario: 临时不可达不伪造能力
- **GIVEN** 静态配置完整但真实 Provider 暂时不可达
- **WHEN** 启动探针超时或收到可重试网络错误
- **THEN** liveness 保持 UP、readiness 为 DOWN、能力不进入冻结快照，业务调用返回明确 Provider 不可用错误

### Requirement: Secret ref 是唯一持久化凭据形式
YAML、数据库、effective snapshot、capability report、日志、Trace、错误响应和 RCA MUST NOT 保存 API Key 或其他解析后的 Secret；系统 MUST 只持久化受支持的 Secret ref，并仅在授权调用边界于内存中解析。配置错误和审计记录 MUST 经脱敏后输出。

#### Scenario: 全链路 Secret 泄漏扫描
- **GIVEN** 使用唯一标记值的测试 Secret 完成成功调用与鉴权失败调用
- **WHEN** 扫描配置、数据库、快照、日志、Trace、错误、审计 Artifact 和 RCA
- **THEN** 标记值在所有持久化与输出中均不存在，仅可见 Secret ref、字段路径或不可逆摘要
