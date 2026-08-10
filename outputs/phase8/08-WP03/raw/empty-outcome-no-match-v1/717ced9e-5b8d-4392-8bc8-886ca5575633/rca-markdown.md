# RCA

- Schema: 1.0.0
- Run: 717ced9e-5b8d-4392-8bc8-886ca5575633
- Outcome: CONCLUSIVE
- Source-Digest: 7aad080b8139970a967d024db6e586765809f001c12c6077960f566040b13347

## Summary

Investigation for incident 015611ee concluded that the inventory-service instance is stopped. Canonical evidence supports this root cause: health.inventory.unreachable (readiness DOWN), log.order.inventory_connection_failed, and metric.prometheus.inventory_target_down (target down 90s). Code analysis identified RETRY_TIMEOUT declarations in order-service files consistent with the connection failure. Knowledge search returned NO_MATCH; trace, config, and topology sources returned no matching signals and were not treated as facts. Missing evidence codes were recorded and do not conflict with the supported hypothesis. Recommended remediation includes restoring the inventory instance, validating client timeout configuration, and adding availability alerting.

## Root Cause

service.instance.stopped.inventory

## Hypotheses

- SUPPORTED: inventory service instance is stopped
- UNVERIFIED: inventory dependency latency is high
- UNVERIFIED: order database connection pool is exhausted

## Citations

- health.inventory.unreachable (`3b7c5b65-0d73-4c31-83da-b23b746d6c53`)
- log.order.inventory_connection_failed (`15e93796-fe65-47fd-b273-346f8226461b`)
- metric.prometheus.inventory_target_down (`873667f0-00e7-46ea-ae60-e11669a100df`)

## Evidence Assessment

- Coverage: 1.0
- Missing: trace.order.inventory_span_latency_high
- Missing: metric.gateway.request_latency_high
- Missing: metric.order.hikari_active_at_max
- Missing: metric.order.hikari_pending_positive
- Missing: log.order.connection_timeout

## Actions

### immediate
- restore.inventory.instance: 立即恢复故障组件
### longTerm
- configure.client.timeout: 修复根因并固化配置
### monitoring
- add.instance.availability.alert: 增加可观测性告警
### tests
### humanNextSteps
### rollback

## Limitations

- Trace, config, and topology sources returned NO_MATCH for the incident window.
- Knowledge search returned NO_MATCH; no known-pattern references were available.
- Code analysis only reported RETRY_TIMEOUT declarations without consuming full raw source bodies.
- No canonical evidence supports connection-pool exhaustion or downstream latency; configure.client.timeout is selected only from RETRY_TIMEOUT code findings and should be validated before change.
