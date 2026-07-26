# Infinity Rerank Provider

## Purpose
定义 Infinity Rerank 的候选身份、响应完整性、部署身份以及中文质量与完整链路性能门禁。

## Requirements

### Requirement: Rerank 请求保持稳定候选身份
Infinity Rerank Adapter MUST 为每个候选发送稳定 `documentId` 并保存原始 index；统一结果 MUST 包含 `documentId`、原 index、有限 score、确定 rank 以及锁定的 provider/model/revision identity。score MUST NOT 被假定归一化到 0 至 1 或跨模型比较。

#### Scenario: 排序结果可回溯原候选
- **GIVEN** 一组顺序固定且 `documentId` 唯一的候选
- **WHEN** Infinity 返回合法重排结果
- **THEN** 每个结果唯一回溯到原候选，保存原 index、score、rank 和实际锁定 identity，最终顺序与 rank 一致

### Requirement: Rerank 响应完整性 fail closed
Adapter MUST 拒绝重复 index、不存在 index、缺失结果、重复 `documentId`、NaN/Infinity score、model mismatch、revision mismatch 和超时部分结果。任何合同错误 MUST 使整次 Rerank 失败，不得保留部分排序或返回原始顺序。

#### Scenario: 响应引用不存在 index
- **GIVEN** 请求只包含 index 0 至 N-1 的候选
- **WHEN** Infinity 响应引用范围外 index 或重复同一 index
- **THEN** 整次调用返回不可作为业务结果的 `ChainFailure`，不生成 `top_k` 或 `rerankApplied=true`

#### Scenario: 超时只收到部分结果
- **GIVEN** Rerank 在父 deadline 前只返回候选子集
- **WHEN** 请求超时或被取消
- **THEN** Adapter 丢弃部分结果并返回超时/取消失败，permit 被释放且候选不会按固定顺序保底

### Requirement: Rerank identity 来自锁定部署事实
Adapter MUST 使用锁定并注入的 `RERANK_MODEL_REVISION`、Infinity 加载清单和镜像 digest 核对模型身份；`/models` 或响应中的可变模型名 MUST NOT 单独作为 revision 证明。identity 不匹配时 capability 探针与业务调用 MUST 失败。

#### Scenario: 响应模型名正确但 revision 锁不匹配
- **GIVEN** Infinity 响应模型名符合配置，但部署加载清单的 revision 与版本锁不同
- **WHEN** 执行启动探针
- **THEN** Rerank 能力不注册、readiness 为 DOWN，并报告可定位的脱敏 identity mismatch

### Requirement: 中文质量与完整链路性能达到冻结门禁
固定中文基准集上的 Rerank 后 NDCG@10 与 MRR MUST 均不低于精确向量基线，且至少一项相对提升 MUST 不低于 5%；固定开发机上 `Embedding → pgvector 精确召回 → Rerank` 完整链路 p95 MUST 小于 2 秒。报告 MUST 记录数据集版本、镜像 digest、模型 revision、License、机器资源、并发和测量方法。

#### Scenario: 质量提升或延迟任一不达标
- **GIVEN** 固定数据集、向量基线、目标机器和锁定 Provider 身份
- **WHEN** 执行可复算质量与性能基准
- **THEN** 只有全部阈值同时满足时门禁通过，否则阶段验收失败且不得以生成模型、二次余弦或固定排序替代
