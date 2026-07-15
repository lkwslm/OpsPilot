# OpsPilot v1 contracts

本目录是 OpsPilot 首版跨模块合同的机器可读事实源。

```text
contracts/
├── openapi/opspilot-v1.yaml
├── schemas/
    ├── rca.schema.json
    ├── ground-truth.schema.json
    ├── scenario.schema.json
    ├── incident-event.schema.json
    ├── evaluation-profile.schema.json
    ├── tool-contracts.schema.json
    ├── a2a-skill-contracts.schema.json
    ├── agent-profile.schema.json
    ├── code-findings.schema.json
    ├── observation-batch.schema.json
    ├── evidence-bundle.schema.json
    └── release-manifest.schema.json
├── profiles/mvp-v1.yaml
└── examples/
    ├── scenarios/
    ├── ground-truth/
    ├── observability/
    ├── code-analysis/
    ├── agent-profiles/
    ├── tools/
    └── release-manifest/
```

实现要求：

1. CI 使用 OpenAPI 3.1 validator 和 JSON Schema Draft 2020-12 validator 校验所有文件与示例；具体 Workflow、状态和发布门禁以[第 26 章](../opspilot-system-design/15-ci-cd-and-release-governance.md)为准。
2. Java/Python DTO、Agent Artifact 校验和 Evaluation 读取均引用这些合同，不复制一套独立字段定义。
3. 新增可选字段更新 minor；删除/重命名字段、改变 required、类型或枚举语义更新 major。
4. 所有 JSON payload 都拒绝未知安全敏感字段；合同中允许扩展的对象才可设置 `additionalProperties: true`。
5. `schemaVersion` 是 payload 合同版本，A2A `protocolVersion` 是传输协议版本，两者不得混用。
6. `release-manifest.schema.json` 是持续交付输出合同；`READY_FOR_MANUAL_DEPLOYMENT` 必须同时满足全部质量门禁通过、数据库无破坏性变更、`automaticDeployment=false` 和 `deploymentPerformed=false`。
7. `observation-batch.schema.json` 固定运行时 Source Adapter 的原始观察；`code-findings.schema.json` 固定代码分析的语言无关中间产物。两者都不是后续分析的事实合同，必须规范化到 `evidence-bundle.schema.json`；Diagnosis、Hypothesis 和 RCA 只引用 Evidence ID。
8. `agent-profile.schema.json` 固定 Agent 角色、逻辑模型引用、输入输出、上下文、Tool/A2A 权限、预算、沙箱、安全和停机策略。Profile 只能收紧平台策略，不能携带密钥、Provider URL 或放宽 Ground Truth/任意命令/代码修改权限。
