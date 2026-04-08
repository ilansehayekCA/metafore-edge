# MQTT Topic Map

All topics are namespaced by tenant and controller for multi-tenant isolation.

## Edge Controller → Core (telemetry)

| Topic | Schema | Description |
|-------|--------|-------------|
| `telemetry/{tenant_id}/{controller_id}/registration` | registration.schema.json | Sent once on startup |
| `telemetry/{tenant_id}/{controller_id}/heartbeat` | heartbeat.schema.json | Every 30 seconds |
| `telemetry/{tenant_id}/{controller_id}/discovery` | discovery_result.schema.json | After discovery run |
| `telemetry/{tenant_id}/{controller_id}/route-results` | route_result.schema.json | After route execution |
| `telemetry/{tenant_id}/{controller_id}/events` | event.schema.json | Log tail events |

## Core → Edge Controller (control)

| Topic | Schema | Description |
|-------|--------|-------------|
| `control/{tenant_id}/{controller_id}/routes` | route_command.schema.json | Deploy/execute/remove routes |
| `control/{tenant_id}/{controller_id}/discovery` | discovery_command.schema.json | Trigger discovery |

## Subscriptions

- **Edge Controller** subscribes to: `control/{tenant_id}/{controller_id}/#`
- **Core** subscribes to: `telemetry/#` (all tenants) or `telemetry/{tenant_id}/#` (single tenant)
