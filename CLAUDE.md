# Metafore Edge — Access Controller Platform

## What This Project Is
Access Controllers are Java/Apache Camel agents that deploy alongside customer systems (databases, APIs, cloud services). They connect to systems of record, execute queries/commands, and report results back to the platform via MQTT.

For POC/cloud deployments, Access Controllers run in Docker with a co-located PostgreSQL. For production, they install on-premise near the customer's systems.

## Reference Implementation
A working Access Controller exists in `C:\metafore-poc\metafore-conduit\`. Key files:
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
    │ Access Controller │
    │  (Java/Camel)  │
    │                │
    │  Route Types:  │
    │  - JDBC        │──── PostgreSQL (default) / Oracle / etc.
    │  - HTTP/REST   │──── SaaS APIs (NetSuite, Salesforce, etc.)
    │  - Shell       │──── OS commands (whitelisted)
    └────────────────┘
```

## What Needs Building
1. **HTTP/REST executor** — Camel HTTP component with OAuth2 support for SaaS integrations
2. **Tenant-namespaced MQTT topics** — `telemetry/{tenant_id}/{controller_id}/...`
3. **Registration API** — Access Controller registers with metafore-core on startup
4. **Secure credential management** — encrypted credential storage, not plain MQTT
5. **Provisioning endpoint** — metafore-core can spin up a cloud Access Controller + managed DB for POC tenants

## Interfaces
### Consumes (from metafore-core via MQTT)
- Route execution commands: `{route_id, sql, parameters, response_topic}`
- Discovery requests: `{type: "on-demand", response_topic}`
- Configuration updates: `{routes: [...], credentials: {...}}`

### Produces (to metafore-core via MQTT)
- Registration: `{controller_id, tenant_id, status, db_type, db_name, capabilities}`
  - `db_name` field comes from `EdgeConfig.dbName()`
  - `db_type` is `"postgresql"` when database is connected
- Heartbeat: `{controller_id, status, uptime, db_stats}`
- Discovery results: `{tables: [...], schemas: {...}}`
- Route results: `{route_id, data: [...], row_count, execution_time}`
- Events: `{type: "log_tail"|"error"|"metric", payload: {...}}`

## Tech Stack
- Java 21, Apache Camel 4, Maven
- Docker (multi-stage build)
- PostgreSQL (co-located for POC, external for production)
- EMQX (MQTT broker)

## Testing Strategy
- **Unit**: JUnit 5 — route logic, parameter substitution, credential handling
- **Integration**: Testcontainers — real PostgreSQL + EMQX in Docker, test full route execution round-trips
- **Contract**: JSON Schema validation on all MQTT messages (must match what metafore-core expects)
- Run: `mvn test` (unit), `mvn verify` (integration)

## JDBC Setup (PostgreSQL)
- PostgreSQL JDBC driver: `org.postgresql:postgresql:42.7.4` in pom.xml
- `PGSimpleDataSource` in DataSourceRegistry.java (replaced MariaDbPoolDataSource)
- Default DB_PORT: 5432
- JDBC URL format: `jdbc:postgresql://{host}:{port}/{dbName}`

## Required Environment Variables
```
CONTROLLER_ID (default: "edge-default")
TENANT_ID (default: "default-tenant") — should be tenant slug, platform resolves to UUID
BROKER_URL (default: "tcp://mqtt-broker:1883")
DB_HOST (default: "localhost") — must be set to "postgresql" in Kubernetes
DB_PORT (default: "5432")
DB_NAME (default: "") — must be set (e.g., "metafore_default")
DB_USER (default: "root") — must be set (e.g., "metafore")
DB_PASS (default: "") — must be set
```

## Dependencies
- None (standalone). Communicates with metafore-core only via MQTT.
