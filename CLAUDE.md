# Metafore Edge — Edge Controller Platform

## What This Project Is
Edge Controllers are Java/Apache Camel agents that deploy alongside customer systems (databases, APIs, cloud services). They connect to systems of record, execute queries/commands, and report results back to the platform via MQTT.

For POC/cloud deployments, Edge Controllers run in Docker with a co-located MariaDB. For production, they install on-premise near the customer's systems.

## Reference Implementation
A working Edge Controller exists in `C:\metafore-poc\metafore-conduit\`. Key files:
- `MetaforeConduit.java` — 5 Camel routes: log tail, heartbeat, startup discovery, on-demand discovery, route executor
- `Dockerfile` — Multi-stage build (Maven → JRE 21 slim)
- Routes execute SQL via JDBC, shell commands via ProcessBuilder, and publish results to MQTT

## Architecture
```
Platform (metafore-core)
    │
    ├── MQTT: telemetry/{tenant_id}/{controller_id}/commands  (inbound)
    │
    └── MQTT: telemetry/{tenant_id}/{controller_id}/events    (outbound)
            │
    ┌───────┴───────┐
    │ Edge Controller │
    │  (Java/Camel)  │
    │                │
    │  Route Types:  │
    │  - JDBC        │──── MariaDB / PostgreSQL / Oracle / etc.
    │  - HTTP/REST   │──── SaaS APIs (NetSuite, Salesforce, etc.)
    │  - Shell       │──── OS commands (whitelisted)
    └────────────────┘
```

## What Needs Building
1. **HTTP/REST executor** — Camel HTTP component with OAuth2 support for SaaS integrations
2. **Tenant-namespaced MQTT topics** — `telemetry/{tenant_id}/{controller_id}/...`
3. **Registration API** — Edge Controller registers with metafore-core on startup
4. **Secure credential management** — encrypted credential storage, not plain MQTT
5. **Provisioning endpoint** — metafore-core can spin up a cloud Edge Controller + managed DB for POC tenants

## Interfaces
### Consumes (from metafore-core via MQTT)
- Route execution commands: `{route_id, sql, parameters, response_topic}`
- Discovery requests: `{type: "on-demand", response_topic}`
- Configuration updates: `{routes: [...], credentials: {...}}`

### Produces (to metafore-core via MQTT)
- Heartbeat: `{controller_id, status, uptime, db_stats}`
- Discovery results: `{tables: [...], schemas: {...}}`
- Route results: `{route_id, data: [...], row_count, execution_time}`
- Events: `{type: "log_tail"|"error"|"metric", payload: {...}}`

## Tech Stack
- Java 21, Apache Camel 4, Maven
- Docker (multi-stage build)
- MariaDB (co-located for POC, external for production)
- Eclipse Mosquitto (MQTT broker)

## Testing Strategy
- **Unit**: JUnit 5 — route logic, parameter substitution, credential handling
- **Integration**: Testcontainers — real MariaDB + Mosquitto in Docker, test full route execution round-trips
- **Contract**: JSON Schema validation on all MQTT messages (must match what metafore-core expects)
- Run: `mvn test` (unit), `mvn verify` (integration)

## Dependencies
- None (standalone). Communicates with metafore-core only via MQTT.
