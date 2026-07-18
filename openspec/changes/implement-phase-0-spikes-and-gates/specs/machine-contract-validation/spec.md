## ADDED Requirements

### Requirement: 合同编译与显式实例映射
系统 MUST（必须）使用锁定版本的 OpenAPI 3.1 validator 和 JSON Schema Draft 2020-12 validator 编译全部机器合同、解析全部 `$ref`，并通过显式映射校验 Profile 和正式示例，不得依赖按扩展名猜测文件用途。

#### Scenario: 所有正式合同通过
- **GIVEN** `docs/design/contracts/` 中的 OpenAPI、Schema、Profile 和示例
- **WHEN** 执行统一合同校验入口
- **THEN** 全部 Schema 编译成功、引用可解析，场景、Ground Truth、Agent Profile、Observation、Evidence、RCA、Evaluation 和 Release Manifest 均被显式覆盖

### Requirement: 错误合同阻止合并
系统 MUST（必须）为错误版本、缺少 required、非法枚举、错误 UUID/哈希、未知安全敏感字段及 `schemaVersion/protocolVersion` 混用提供独立负向 fixture，并证明每类错误都会使本地与 CI 校验失败。

#### Scenario: 独立负向样例失败
- **GIVEN** 每次仅启用一种错误合同 fixture
- **WHEN** 分别运行本地合同命令和 `contracts.yml` 中的相同入口
- **THEN** 两个入口均以非零退出码结束，并报告预期错误而非其他偶发错误

### Requirement: OpenAPI 与文档完整性
系统 MUST（必须）校验 OpenAPI operationId、响应 Schema、引用完整性以及阶段文档和设计文档的本地 Markdown 链接。

#### Scenario: 断开的引用被发现
- **GIVEN** 一个不存在的 OpenAPI `$ref` 或本地 Markdown 链接
- **WHEN** 执行合同校验
- **THEN** 校验失败并定位源文件与断开目标

### Requirement: 可审计的合同报告
`contracts.yml` MUST（必须）执行与本地相同的校验逻辑，并上传包含 validator 版本、文件数、成功/失败数和输入哈希的报告；工作流不得部署环境。

#### Scenario: CI 生成合同证据
- **GIVEN** 正式合同均有效且所有负向 fixture 的预期已验证
- **WHEN** GitHub Actions 执行 `contracts.yml`
- **THEN** 工作流成功并上传可追溯报告，且没有任何部署步骤或目标环境凭证
