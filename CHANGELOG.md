# metafore-edge changelog

## 1.1.0 — 2026-05-16 — Phase 14.9 edge topology advertisement

- **Add `EdgeRuntime` enum** (`docker | native | docker-host-network | unknown`) classifying the surrounding process topology. Wire form is kebab-case (matches `contracts/mqtt/registration.schema.json` enum).
- **Add `RuntimeProbe`** — layered auto-detection (`EDGE_RUNTIME` env override → `/.dockerenv` file → `/proc/1/cgroup` markers → `HOSTNAME` heuristic → host-network upgrade via `/proc/net/dev` → JVM `os.name` fallback → `UNKNOWN`). Injectable `Probes` record makes the probe unit-testable without spelunking the host filesystem.
- **Extend `EdgeConfig`** with `runtime()` + `runtimeHints()` accessors. The probe runs once at construction; result is cached. Hints whitelist 5 keys (`os_name`, `java_version`, `hostname`, `docker_env_file_present`, `cgroup_signature`) — no env-var dump.
- **Extend `MessageFactory.registration`** with a 7-arg overload taking `runtime` + `runtimeHints`. The legacy 5-arg overload is preserved; it omits both fields (back-compat invariant — older payloads still validate against the additive schema).
- **`RegistrationRoute`** now passes `config.runtime()` + `config.runtimeHints()` so every edge registration MQTT payload advertises its topology.
- **Bump `EDGE_VERSION`** default `1.0.0 → 1.1.0` to signal the additive wire-protocol extension. No client-side gating; older cores ignore the new fields.

Anchor incident: 2026-05-16 — a Source authored against `host.docker.internal` survived an AC restart that switched from Docker to native Java; the JDBC connection silently failed because `host.docker.internal` is a Docker-engine hosts-file alias that doesn't exist on a native Windows JVM. Surfacing the runtime as part of registration lets metafore-core flag the topology mismatch at mount time (see brief `BRIEF_PHASE_14_9_EDGE_TOPOLOGY_ADVERTISEMENT.md`).

Pairs with `mount-test-before-commit` (Brief 14.12) — Phase 14.9 produces the runtime signal that 14.12's pre-commit connectivity check consumes.
