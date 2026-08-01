## Purpose

该能力用于在可重复的固定开发机、数据规模与并发模型下验证产品接口、检索链、端到端恢复及资源稳定性，并保存足以复算的原始样本。

## ADDED Requirements

### Requirement: 性能测试必须使用冻结负载模型和可复算统计方法

系统 SHALL 在记录身份的固定开发机上准备 50,000 个 `searchable=true` active Chunk，并以 20 检索 QPS、固定黄金查询、明确并发模型、独立预热窗口和测量窗口执行测试。报告 MUST 保存样本数、失败样本、p50/p95/p99、吞吐、错误率、统计公式、查询集/配置/数据 revision digest 以及 CPU、内存、GPU、连接和 OOM 数据。

#### Scenario: 可重复执行固定负载
- **GIVEN** 数据、模型、过滤条件、机器和并发配置均与冻结快照一致
- **WHEN** 性能 harness 完成预热和测量窗口
- **THEN** 原始逐请求样本与聚合统计均被保存，预热样本不进入正式分位数，且报告可由原始数据独立复算

#### Scenario: 测量期间负载未达到二十 QPS
- **GIVEN** harness 因客户端或环境限制未维持目标负载
- **WHEN** 性能报告汇总样本
- **THEN** 本次测试标记为无效或 `BLOCKED`，不得用较低实际 QPS 的延迟声明门禁通过

### Requirement: 固定延迟与恢复目标必须全部满足

系统 SHALL 验证产品非流式读 API p95 `<500ms`、SSE 已提交事件重放首事件 p95 `<1s`、精确向量 candidate recall p95 `<300ms`、完整 Embedding+召回+Rerank p95 `<2s`、单 Incident 端到端 p95 `<10min`，以及进程重启后 `60s` 内完成对账并继续或明确失败。所有不等式 MUST 按严格小于判定。

#### Scenario: 延迟恰好等于上限
- **GIVEN** 任一正式测量的 p95 恰好等于对应阈值
- **WHEN** 性能门禁判定
- **THEN** 该项为 `FAILED`，报告保留原始样本与严格小于规则

#### Scenario: 重启后明确失败但及时完成对账
- **GIVEN** 重启后上游状态证明 Run 无法安全继续
- **WHEN** 系统在 60 秒内完成对账并进入带完整原因的 `FAILED`
- **THEN** 恢复时限项通过，但 Run 的功能/质量结果仍按其失败状态独立判定

### Requirement: 连续运行期间资源必须保持稳定

系统 SHALL 在 15 次正式质量 Run 前后和运行期间采集进程/容器 CPU、内存、GPU、数据库与 HTTP 连接、线程/任务、OOM 和状态完整性。15 Run MUST 无 OOM、无连接泄漏、无状态丢失；资源增长判定 SHALL 使用预先冻结的采样与容差规则，而非运行后选择区间。

#### Scenario: 连续运行后连接未回落
- **GIVEN** 15 次运行已结束并超过冻结的稳定等待窗口
- **WHEN** 资源验证器比较基线与结束连接状态
- **THEN** 超过冻结容差且无法由活动工作解释的连接增长使性能资源门禁 `FAILED`

### Requirement: 开发机阈值不得冒充生产容量承诺

性能报告 SHALL 标明硬件、驱动、容器资源限制、测试负载与“非生产 SLO”边界。若固定开发机未达标，系统 MUST 失败或使用预先声明的新 Profile/资源配置重启完整批次，不得删除 Rerank、减少 required Evidence 或关闭真实模型测试。

#### Scenario: 通过删减真实链路改善延迟
- **GIVEN** 为满足阈值而关闭 Rerank 或减少 required Evidence
- **WHEN** harness 校验冻结调用链
- **THEN** 测试无效且门禁 `FAILED`，不得发布该性能结果
