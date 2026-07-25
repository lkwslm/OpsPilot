# Purpose

定义阶段 04 真实 Compose 纵切、Jaeger 双版本兼容矩阵和可复现 CI 验收门禁。

## Requirements

### Requirement: Compose 组装阶段 04 的真实依赖
Compose MUST 组装三个 Sample 服务、PostgreSQL、Prometheus、长期并行兼容的 Jaeger v1/v2、OTel Collector、Toxiproxy 和本阶段纵切所需的 OpsPilot 依赖；所有被依赖服务 MUST 提供健康检查并通过健康条件控制启动顺序，配置 MUST 可由 `docker compose config` 静态验证。

#### Scenario: 从干净环境启动 Compose
- **GIVEN** 阶段锁定的镜像、环境模板和一个干净的 Compose project
- **WHEN** 执行配置校验、镜像构建/拉取并启动环境
- **THEN** 配置解析成功，Migration 完成，所有依赖在有界时间内健康且 Sample 业务入口可调用

### Requirement: Compose smoke 覆盖 Jaeger 双版本兼容矩阵
本地和 CI smoke MUST 分别覆盖 Jaeger v1-only、v2-only、v1+v2 三种配置，并对每个已启用版本验证健康、Trace 导出、Adapter 查询和 canonical Observation。v1+v2 模式 MUST 验证显式多 Source 查询、独立 Batch、无静默 failover、重复 Trace 去重与双方 provenance；兼容门禁 MUST NOT 通过删除或禁用 v1 来满足。

#### Scenario: 运行 Jaeger 版本兼容矩阵
- **GIVEN** 三种 Compose 配置均使用锁定且可审计的 Jaeger 镜像与对应 Source 配置
- **WHEN** smoke 依次运行 v1-only、v2-only、v1+v2 纵切
- **THEN** 两个单版本配置分别完成真实 Evidence 链，双版本配置完成显式并行查询并保留两个来源，且 v1 始终是受支持能力

### Requirement: 纵切产生可追溯的真实 Evidence 链
smoke MUST 跑通“业务请求 → 遥测后端 → Source Adapter → Observation → Evidence/Artifact”的真实纵切，并证明关联日志、指标、Trace、健康或拓扑来自实际 Source。每条最终 Evidence MUST 可回溯到 Batch、Record、Source 和有效 Artifact。

#### Scenario: 一次订单请求进入 Evidence 层
- **GIVEN** Compose 全部依赖健康且 Source Registry 已发布 capability snapshot
- **WHEN** smoke 发起一次带关联标识的订单请求并在有界窗口查询遥测
- **THEN** 系统保存 Schema 合法的 Observation、不可变 Evidence/provenance 和必要 Artifact，来源、资源、查询哈希与关联上下文一致

### Requirement: CI smoke 验证入口、Migration 与依赖健康
`.github/workflows/compose-smoke.yml` MUST 在 CI 中验证 Compose config、镜像入口、PostgreSQL Migration、依赖健康、阶段 04 纵切和清理；失败时 MUST 保存足以定位问题的日志、容器状态和测试报告。

#### Scenario: Migration 或依赖健康失败
- **GIVEN** CI 中某个 Migration 失败或遥测依赖在 deadline 内未健康
- **WHEN** compose smoke 工作流执行
- **THEN** 工作流失败、后续纵切不被标记成功、诊断 Artifact 被保存且清理步骤仍执行

### Requirement: smoke 不冒充后续阶段门禁
compose smoke MUST NOT 使用 Fake Provider 声称模型、Agent、RCA、Evaluation 或完整 E2E 通过；阶段 04 成功结果 MUST 明确限定为 Sample 与可观测 Evidence 纵切。Agent 和 Tool MUST NOT 因 smoke 获得 Docker socket。

#### Scenario: 未配置真实模型 Provider
- **GIVEN** Compose 环境只具备阶段 04 所需依赖且未配置真实模型 Provider
- **WHEN** compose smoke 完成本阶段纵切
- **THEN** 工作流可以报告阶段 04 smoke 成功，但不会生成或宣称模型、Agent、RCA、Evaluation 或完整 E2E 通过

### Requirement: smoke 证据可复现且清理恢复基线
本地和 CI smoke MUST 记录命令、退出码、日志/报告 URI 与 SHA-256，并在成功或失败后停止故障、恢复代理、释放长事务和清理 Compose project；清理后 MUST 验证无测试故障残留。

#### Scenario: 纵切在故障验证期间中止
- **GIVEN** Toxiproxy 或应用内故障已启用且 smoke 中途失败
- **WHEN** 工作流进入清理阶段
- **THEN** reset 与代理恢复被幂等执行，容器和测试资源被清理，诊断证据保留且后续运行可从基线开始
