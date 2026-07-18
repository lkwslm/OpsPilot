# Phase 0 manual deployment runbook

Phase 0 only produces a release candidate for manual review. CI and the local gate never deploy or push an image.

1. Review `outputs/phase0/01-WP10/release-manifest.json` and confirm `automaticDeployment=false` and every quality gate is `PASSED`.
2. Confirm the image ID and model revisions match `deployment/versions.lock.yaml`.
3. Start the local six-process topology with `docker compose -f deployment/docker-compose.yml up -d`.
4. Verify all six endpoints are healthy and only `127.0.0.1:8080` is published.
5. Stop it manually with `docker compose -f deployment/docker-compose.yml down` when the review is complete.

No target-environment credentials are stored in this repository or its workflows.
