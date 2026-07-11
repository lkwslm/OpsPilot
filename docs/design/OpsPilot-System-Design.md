# OpsPilot 系统完整设计

> 版本：系统设计 v2.1  
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

## 阅读约定

- 章节编号是当前设计的规范编号。
- 跨章节修改时应同步检查上游约束、下游测试和关键设计决策。
- A2A 协议基线、ReAct loop、Agent 协作、知识不足和失败透明策略以第 3、8、9 分卷为准。
