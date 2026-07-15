# OpsPilot 系统完整设计

> 版本：系统设计 v2.4
> 适用范围：MVP / 本地集成测试 / 后续生产化演进  
> 状态：正式设计；可直接指导实现；文中标为“待确认”的项目在编码或部署前必须闭环

本设计已按主题拆分。本文仅保留导航，设计正文以以下分卷为准。

## 文档导航

1. [目标、约束与总体架构](opspilot-system-design/01-overview-and-scope.md)
2. [模块与边界](opspilot-system-design/02-modules-and-boundaries.md)
3. [A2A 多 Agent 架构](opspilot-system-design/03-a2a-multi-agent-architecture.md)
4. [模型配置与 Token 预算](opspilot-system-design/04-model-configuration-and-token-budget.md)
5. [数据与向量存储](opspilot-system-design/05-data-and-vector-storage.md)
6. [模型与检索 Provider](opspilot-system-design/06-model-and-retrieval-providers.md)
7. [本地模型部署与启动](opspilot-system-design/07-local-model-deployment-and-bootstrap.md)
8. [可靠性、安全与可观测性](opspilot-system-design/08-reliability-security-and-observability.md)
9. [测试策略](opspilot-system-design/09-test-strategy.md)
10. [部署与实施路线](opspilot-system-design/10-deployment-and-roadmap.md)
11. [关键设计决策、待确认项与附录](opspilot-system-design/11-design-decisions-and-appendices.md)
12. [实现合同与演进规则](opspilot-system-design/12-implementation-contracts-and-evolution.md)
13. [首版运行拓扑与 Phase 0 门禁](opspilot-system-design/13-runtime-topology-and-phase0-gates.md)
14. [首版故障场景与确定性评测](opspilot-system-design/14-scenarios-and-deterministic-evaluation.md)
15. [CI、持续交付与发布治理](opspilot-system-design/15-ci-cd-and-release-governance.md)
16. [分布式目标系统与可观测数据适配](opspilot-system-design/16-distributed-target-and-observability-adapters.md)
17. [机器可读 OpenAPI 与 JSON Schema](contracts/README.md)

## 阅读约定

- 章节编号是当前设计的规范编号。
- 跨章节修改时应同步检查上游约束、下游测试和关键设计决策。
- A2A 协议基线、ReAct loop、Agent 协作、知识不足和失败透明策略以第 3、8、9 分卷为准。
- 跨模块字段、状态和评测解释冲突时，以第 23 章规定的合同优先级为准；OpenAPI/JSON Schema 是实现和合同测试的机器可读事实源。
- Phase 0 门禁通过前只允许骨架与 Spike；通过后，本设计作为首版完整开发和验收基线。
- 第 26 章中的 CD 只指持续交付：GitHub Actions 生成可验证发布候选后停止，任何目标环境部署均由流水线之外的人工运维流程执行。
- OpsPilot 核心与被诊断系统的语言、运行平台和可观测产品边界以第 27 章为准；Java/Spring、Prometheus 和 Jaeger 是首期验证实现，不是产品接入前提。
