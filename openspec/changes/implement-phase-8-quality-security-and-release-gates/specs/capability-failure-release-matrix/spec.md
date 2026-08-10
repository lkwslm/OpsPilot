## Purpose

该能力用于通过可枚举的组件、故障类型和能力关键性矩阵，证明技术故障按有限重试、明确终止或受限缺失的合同处理，且不会触发隐藏降级。

## ADDED Requirements

### Requirement: 技术失败目录必须覆盖全部真实调用边界

系统 SHALL 对 LLM、Embedding、Rerank、KnowledgeAgent、五个专业 A2A endpoint，以及每个已登记 Tool 和 Source 分别执行不可用、超时、鉴权失败与 Schema 错误用例。每个 case MUST 记录组件/endpoint、`sourceId/sourceKind/adapterId`、`routeKind/routeRef`、`topologyRef/triggerRef`、触发前置条件、预期调用证据、注入故障、attempt、deadline、上游状态、checkpoint、关联 ID、`logArtifactId` 与期望关键性。`routeRef` MUST 指向真实网络、文件或进程边界；Tool 必须绑定其实际下游边界，不得替换 Tool 实现或直接开关生产返回值。

#### Scenario: 生成完整失败矩阵
- **GIVEN** 当前冻结 Profile、Agent Card、Tool Registry 和 Source Registry 已加载
- **WHEN** 失败目录生成器展开测试 case
- **THEN** 每个已登记真实调用边界都有四类故障 case，每个 case 均能解析到冻结的拓扑和触发项，且双向目录差异校验能发现遗漏、孤立项或未登记新增项

### Requirement: 统一故障控制面必须保留真实数据面语义

系统 SHALL 通过单一控制面激活、恢复和检查每个 case，并将每个 catalog `routeRef` 唯一解析到包含消费服务配置、真实上游、注入端点、健康探针、残留探针和恢复动作的 Compose 拓扑项。网络断连/超时 MUST 使用 Toxiproxy，HTTP 鉴权/Schema MUST 使用受控响应代理；文件边界 MUST 通过缺失路径、受控阻塞读取、权限拒绝或版本化畸形 fixture 触发原 Adapter；进程边界 MUST 通过不可用、挂起、执行权限拒绝或畸形子进程协议触发原 Adapter。每次激活 MUST 只改变目标 `routeRef`，恢复后 MUST 验证目标健康且不存在 residual toxic、响应规则、文件/FIFO、权限、挂载或进程故障。

#### Scenario: catalog 路由无法解析到实际数据面
- **GIVEN** failure catalog 已冻结但一个 `routeRef` 缺少拓扑项、消费服务配置或健康探针
- **WHEN** runner 准备对应 case
- **THEN** case 在改变环境前标记为 `BLOCKED`，不得仅凭 injector 服务已启动继续执行

#### Scenario: 本地 Tool 被错误改成代理 Tool
- **GIVEN** Tool 的真实下游是文件、知识链或本地沙箱边界
- **WHEN** 失败 runner 执行该 Tool 的技术失败 case
- **THEN** 系统在 catalog 指定的真实 `routeRef` 注入并观察原 Tool 调用，若使用替代 Tool、直接返回开关或未调用真实边界则门禁为 `FAILED`

#### Scenario: case 恢复后仍有路由残留
- **GIVEN** 一个网络、文件或进程故障 case 已完成
- **WHEN** 控制面执行恢复和健康检查
- **THEN** 目标恢复到冻结路由且无其他 route 被改变；任一残留或健康失败阻断后续可变 case

### Requirement: 每个失败 case 必须触发并证明真实目标调用

系统 SHALL 为每个 case 冻结 `scenarioVersion`、ticket/input fixture、关键性前置条件、预期 `componentId/routeRef` 和调用证据选择器，并使用独立 `FAILURE_INJECTION` Run 在故障激活窗口内执行。case 只有在故障生效、目标真实调用、产品终态或受限结果、恢复健康与无残留证据全部存在后，才可进入关键性断言；控制面注入回执本身不得作为目标已调用的证明。

#### Scenario: 关键性前置条件无法建立
- **GIVEN** 一个 case 要求候选存在、独立 Source 成功或 Sandbox 已批准
- **WHEN** runner 在激活故障前无法建立或验证该前置条件
- **THEN** case 标记为 `BLOCKED`，不改变任何 route，也不将未执行结果判为通过

#### Scenario: 调查未调用目标组件
- **GIVEN** 控制面已在目标 `routeRef` 激活故障
- **WHEN** 调用账本、Trace、A2A Task 事件和 Adapter 证据均不能证明目标组件在激活窗口内被调用
- **THEN** case 标记为 `FAILED`，即使 injector 返回激活成功也不得进入关键性断言

#### Scenario: 目标通过错误路由调用
- **GIVEN** 调查调用了预期组件
- **WHEN** 实际调用路由与冻结 `routeRef` 不同或任一非目标 route 被改变
- **THEN** case 标记为 `FAILED` 并记录路由差异，不得用该结果证明真实故障行为

#### Scenario: 真实调用与恢复证据完整
- **GIVEN** case 的前置条件、拓扑和触发项均有效
- **WHEN** 独立 Run 通过冻结路由调用目标并完成恢复
- **THEN** 逐 case Artifact 同时保存故障生效、调用身份、错误链或受限结果、路由恢复、健康和无残留证据的 URI、大小与实际 SHA-256，再按能力关键性判定结果

### Requirement: 关键性必须决定技术失败结果

系统 SHALL 按场景合同执行 `MANDATORY`、`MANDATORY_WHEN_CANDIDATES_EXIST`、`CONDITIONAL` 和 `OPTIONAL_APPROVED` 语义。关键能力在有限重试耗尽后 MUST 使 step 与 Incident `FAILED`；仅明确允许继续的缺失 SHALL 产生带 `ChainFailure` 的结构化 `missingEvidence` 和受限报告。

#### Scenario: MANDATORY 能力耗尽重试
- **GIVEN** LLM、Artifact、PostgreSQL、关键 A2A endpoint、LogQueryTool 或 HealthQueryTool 发生持续技术故障
- **WHEN** 有界重试在父级 deadline 内耗尽
- **THEN** step 与 Incident 进入 `FAILED`，完整错误链可定位，且不得生成伪成功 RCA

#### Scenario: MANDATORY_WHEN_CANDIDATES_EXIST 的 Rerank 故障
- **GIVEN** 知识检索已产生候选且 Rerank 不可用、超时、鉴权失败或 Schema 无效
- **WHEN** 有界重试耗尽
- **THEN** Incident 进入 `FAILED`，不得以 vector-only、关键词或固定排序继续

#### Scenario: CONDITIONAL Source 被允许缺失
- **GIVEN** 场景未要求该类 Evidence、至少一种独立观测源成功且当前假设满足继续条件
- **WHEN** 对应 Source 技术失败
- **THEN** 系统记录 `ChainFailure + missingEvidence` 并生成明确限制的报告，不把故障伪装为空结果

#### Scenario: 已批准 Sandbox 技术失败
- **GIVEN** `SandboxTestTool` 已获批准并开始执行
- **WHEN** 沙箱发生技术故障
- **THEN** Incident 进入 `FAILED`；未批准、被拒绝或审批超时才允许不执行并生成受限报告

### Requirement: 失败路径不得自动切换或污染质量分母

系统 MUST 禁止未显式配置的 Provider/Source failover、删减检索步骤以及 Mock/vector-only/关键词/固定结果保底。专门失败注入 Run SHALL 独立标记并排除在 15 次正常质量指标分母之外，但其门禁失败 MUST 阻断阶段发布。

#### Scenario: Provider 技术故障时发生隐藏切换
- **GIVEN** 冻结 Provider 发生技术故障
- **WHEN** 调用账本显示路由到其他 Provider 或替代算法
- **THEN** 技术失败门禁直接为 `FAILED`，报告列出未授权路由，且该结果不得进入质量聚合
