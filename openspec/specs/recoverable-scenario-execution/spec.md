## Purpose

定义 Fault Lab 所有场景共享的确定顺序、checkpoint、UTC 时间线和失败恢复合同，确保取消、组件失败及恢复失败都不会静默遗留故障状态。

## Requirements

### Requirement: 场景按统一阶段序列执行
每个场景 MUST 严格按“重置 → 健康 → 基线 → 注入 → 持续负载 → 采集 → 恢复 → 导出 → 校验”推进；每一步 MUST 记录 UTC 开始/结束、输入摘要、输出 Artifact、checkpoint 和失败原因，后一步 MUST NOT 在前一步未满足门禁时启动。

#### Scenario: 基线失败阻止注入
- **GIVEN** 环境已重置但基线成功率或健康条件不满足冻结 predicate
- **WHEN** runner 完成基线检查
- **THEN** runner 不执行故障注入，记录基线失败并进入恢复与导出路径

### Requirement: 失败与取消始终执行恢复
runner MUST 在 `finally` 语义下对成功、失败和取消执行幂等恢复；恢复失败 MUST 与原始失败分别记录，MUST NOT 覆盖原始错误、伪造成功或跳过剩余安全清理。

#### Scenario: 原始失败与恢复失败同时发生
- **GIVEN** 采集步骤失败且 FaultInjector 的首次恢复也失败
- **WHEN** runner 收敛该执行
- **THEN** 时间线保留采集错误和恢复错误两个独立记录，最终状态为失败且后续环境校验仍被执行

### Requirement: 恢复后验证无残留故障
恢复阶段 MUST 重新检查服务健康、代理 toxic、故障开关、受控事务、容器身份和数据状态；只有全部恢复 predicate 满足时才可标记环境已恢复，重复 reset MUST 保持幂等。

#### Scenario: toxic 未清除
- **GIVEN** 场景调用恢复后 Toxiproxy 中仍存在本次 datasetRunId 对应的 toxic
- **WHEN** HealthChecker 执行恢复后核验
- **THEN** 数据集被标记为无效且 runner 报告残留故障，不把业务请求恢复误判为清理完成

### Requirement: Docker 控制权仅属于 Fault Lab
只有 Fault Lab 服务身份 MUST 获得受控 Docker 操作能力；Agent、Tool、产品 API 和 Evaluation MUST NOT 获得 Docker socket、等价代理 API 或可转交的控制凭证。

#### Scenario: Agent 探测 Docker 控制面
- **GIVEN** Agent 在运行容器内探测 Docker socket、Fault Lab 控制凭证和等价控制端点
- **WHEN** 执行隔离验证
- **THEN** 所有路径均不可访问且产生安全审计，场景执行能力不受 Agent 输入影响
