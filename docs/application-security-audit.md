# Application dependency security audit

Verified on 2026-10-04 with Trivy 0.75.0 and vulnerability database snapshot
`2026-10-03T19:02:38Z`. The final database refresh returned the same snapshot.
The repository scan covers Maven dependency resolution, npm and both uv
lockfiles, with unfixed findings included and no vulnerability suppressions.

| Target | Baseline CRITICAL | Baseline HIGH | Final CRITICAL | Final HIGH |
| --- | ---: | ---: | ---: | ---: |
| audio-detector/uv.lock | 1 | 22 | 0 | 0 |
| eureka-server/pom.xml | 9 | 27 | 0 | 0 |
| file-service/pom.xml | 9 | 44 | 0 | 0 |
| frontend/package-lock.json | 1 | 2 | 0 | 0 |
| gateway/pom.xml | 3 | 46 | 0 | 0 |
| orchestrator/pom.xml | 9 | 51 | 0 | 0 |
| video-detector/uv.lock | Not previously locked | Not previously locked | 0 | 0 |

These counts are package/advisory occurrences; shared dependencies appear in
multiple services. npm audit also reports zero vulnerabilities of any severity
for the installed frontend graph. A root-filesystem scan of the four freshly
built Spring Boot JARs reports zero HIGH/CRITICAL findings.

## Changes and compatibility

Keep Java services on the Spring Boot 4.0 / Spring Cloud 2025.1 release lines,
updating to 4.0.8 / 2025.1.3. Pin the supported patched versions of Jackson 2/3,
Netty, Tomcat, Micrometer, HttpCore 5, Bouncy Castle, FreeMarker, PostgreSQL JDBC
and the RabbitMQ client in each independently built Maven project. Both parent
upgrades and the effective transitive overrides were checked by building/testing
all four services and scanning their resolved dependencies and runtime JARs.

The frontend updates React Router within major 7, websocket-driver and the
compatible transitive dependency fixes selected by npm audit. Lint, all 106
Vitest tests and the production build pass.

Both detectors use Lightning 2.6.6 and minimum patched versions for AnyIO,
aiohttp, Pillow, msgpack, Starlette and urllib3. Audio's old Transformers upper
bound prevented applying its security fixes; migrate to 5.18.0 and use its
`freeze_feature_encoder` API. The offline Wav2Vec2 test checks frozen feature
parameters, forward/backward execution, Lightning checkpoint reload, dynamic
ONNX export and ONNX Runtime parity at two waveform lengths and batch sizes.
The video test checks temporal gradients, attention normalization and Lightning
checkpoint reload without downloading pretrained weights.

Pin video torchvision to the same CPU index as torch; mixing CPU torch and a
PyPI/CUDA torchvision wheel caused a missing `torchvision::nms` operator on Linux.
Add the video lockfile and make both CPU Dockerfiles install their exact frozen
uv graph with uv 0.12.23. CI checks the dependency scan and npm audit, then installs
both complete frozen Python graphs on Linux/Python 3.12 and runs consumer and
real ML library compatibility tests in separate processes. The explicit legacy
ONNX export keeps the existing opset 14 / dynamic-axes inference contract; its
upstream deprecation warnings are visible.

## Verification and limits

Local Maven verify passed for gateway, eureka-server, file-service and
orchestrator, including Docker-backed integration tests. Audio has 23 passing
consumer/extraction tests; video has 34. Both offline ML compatibility tests pass
on Python 3.12 with the patched ML libraries. CI verifies the frozen graphs on
Linux; local ML checks used macOS/ARM64.

Not performed: full production-weight inference/training, accuracy or
performance comparison, full detector Docker builds (including model assets),
browser end-to-end upload/analysis, or live deployment. Small deterministic
fixtures establish API/checkpoint/export compatibility, not real-model accuracy.
Infrastructure images and their remaining findings are handled separately in
PR #73. Its image results are not included in this application-only zero count.
