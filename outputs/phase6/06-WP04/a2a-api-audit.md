# A2A v1.0.1 API audit

Status: **PASS — implementation may proceed**

## Locked evidence

- Protocol release: `a2aproject/A2A` tag `v1.0.1` (`3303592`), released 2026-05-28. Its changelog records HTTP binding preference for `application/a2a+json` and TaskStatus fixes.
- Protocol wire version: `1.0`; the locked SDK reports `AgentInterface.CURRENT_PROTOCOL_VERSION == "1.0"`.
- Java object coordinate: `org.a2aproject.sdk:a2a-java-sdk-spec:1.1.0.Final`, locked in the parent POM. The local artifact exposes official `AgentCard`, `Message`, `Task`, `Artifact`, `TaskStatus`, all eight valid `TaskState` values, and invalid `UNRECOGNIZED`.
- Client API: the official Java SDK documents `Client.subscribeToTask(TaskIdParams)` for ongoing tasks. OpsPilot keeps its own narrow client while mapping all wire-domain objects through the official spec artifact.
- Binding: HTTP+JSON is advertised through `TransportProtocol.HTTP_JSON`; requests use `Content-Type: application/a2a+json` and `A2A-Version: 1.0`.
- License: the published SDK parent POM declares Apache License 2.0 and SCM `https://github.com/a2asdk/a2a-java-sdk`.

## Phase 0 differences resolved by WP04

| Area | Phase 0 | WP04 decision |
|---|---|---|
| Request media type | `application/json` | fixed `application/a2a+json` |
| Version dispatch | implicit | required `A2A-Version: 1.0` |
| Caller and target | not validated | service identity, target Agent and skill checked before store access |
| Extensions | not declared | correlation extension declared required; unknown required extensions fail closed |
| States | five-state subset | all eight official states mapped; invalid sentinels rejected |
| Official objects | Card only | bidirectional Card/Message/Task/Artifact boundary mapper |
| Result contract | minimal JSON artifact | media/schema/task/run/owner/hash/reference metadata required |

## Sources

- https://github.com/a2aproject/A2A/releases/tag/v1.0.1
- https://github.com/a2aproject/A2A/blob/v1.0.1/docs/specification.md
- https://github.com/a2aproject/a2a-java
- Local Maven POM: `~/.m2/repository/org/a2aproject/sdk/a2a-java-sdk-parent/1.1.0.Final/a2a-java-sdk-parent-1.1.0.Final.pom`
