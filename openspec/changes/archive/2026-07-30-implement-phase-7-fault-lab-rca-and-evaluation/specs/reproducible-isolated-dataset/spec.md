## Purpose

定义一次故障实验的数据集身份、三时间窗、环境摘要、Artifact 完整性和目录/凭证隔离，使 Agent 输入可复现而 Ground Truth 与执行细节不可见。

## ADDED Requirements

### Requirement: 每次执行生成唯一且完整的数据集身份
每次场景执行 MUST 生成唯一 `datasetRunId` 和确定随机种子，并记录 Git commit、Compose 配置 digest、镜像 digest、模型配置摘要、场景版本、baseline/fault/recovery 三个 UTC 时间窗及全部 Artifact URI/SHA-256；所有记录 MUST 绑定同一 datasetRunId。

#### Scenario: 相同场景和种子重复执行
- **GIVEN** 相同 commit、镜像、模型配置、场景版本和随机种子
- **WHEN** 场景独立执行两次
- **THEN** 两次生成不同 datasetRunId，同时保留可比较的相同环境摘要和各自完整 Artifact 哈希清单

### Requirement: 固定目录和身份实现信息隔离
数据集 MUST 使用 `input/`、`ground-truth/`、`execution/` 固定目录；Agent MUST 只能只读访问 `input/`，Ground Truth 只能由 Fault Lab 和 Evaluation 读取，`execution/` 的路径、内容、卷和凭证 MUST NOT 挂载、记录或暴露给 Agent、产品 API、日志和 Prompt。

#### Scenario: Agent 枚举受限目录和凭证
- **GIVEN** 一个正在分析数据集的 Agent 服务身份
- **WHEN** 它枚举挂载、环境变量、数据库 Schema、API、日志和 Prompt 上下文
- **THEN** 只能读取授权的 `input/` 内容，无法发现或读取 ground-truth/execution 路径、卷或凭证

### Requirement: 数据集导出执行全量一致性校验
DatasetValidator MUST 复算 Artifact SHA-256，校验三时间窗有序且不非法重叠、required 文件齐全、datasetRunId 跨文件一致、引用目标存在且不得越过允许目录；任一失败 MUST 使数据集无效。

#### Scenario: 跨目录引用 Ground Truth
- **GIVEN** `input/manifest` 中存在指向 `ground-truth/` 的相对或规范化后路径
- **WHEN** DatasetValidator 解析并校验引用
- **THEN** 校验 fail closed，报告越权引用且该数据集不得交给 Agent 或 Evaluation
