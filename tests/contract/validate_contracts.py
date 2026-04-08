"""
Contract validation for metafore-edge.

Validates that all test fixtures conform to (or intentionally violate) the shared schemas.
Schemas come from the contracts/ submodule (metafore-contracts).

Run: python -m pytest tests/contract/validate_contracts.py -v
Requires: pip install jsonschema
"""

import json
import os
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, ValidationError

CONTRACTS_DIR = Path(__file__).parent.parent.parent / "contracts"
FIXTURES_DIR = Path(__file__).parent / "fixtures"

# Map fixture filenames to their schema files
SCHEMA_MAP = {
    "heartbeat": "mqtt/heartbeat.schema.json",
    "route_result": "mqtt/route_result.schema.json",
    "discovery_result": "mqtt/discovery_result.schema.json",
    "event": "mqtt/event.schema.json",
    "registration": "mqtt/registration.schema.json",
    "route_command": "mqtt/route_command.schema.json",
    "discovery_command": "mqtt/discovery_command.schema.json",
}


def load_schema(schema_path: str) -> dict:
    full_path = CONTRACTS_DIR / schema_path
    assert full_path.exists(), f"Schema not found: {full_path} — is the contracts submodule initialized?"
    with open(full_path) as f:
        return json.load(f)


def load_fixture(fixture_path: Path) -> dict:
    with open(fixture_path) as f:
        return json.load(f)


def fixture_to_schema_key(filename: str) -> str:
    """Map fixture filename to schema key, handling suffixes like heartbeat_missing_status."""
    name = filename.replace(".json", "")
    for key in SCHEMA_MAP:
        if name == key or name.startswith(key + "_"):
            return key
    return None


def collect_fixtures(subdir: str):
    """Collect all fixture files from a subdirectory."""
    fixtures_path = FIXTURES_DIR / subdir
    if not fixtures_path.exists():
        return []
    return list(fixtures_path.glob("*.json"))


# --- Valid fixtures: must pass validation ---

valid_fixtures = collect_fixtures("valid")


@pytest.mark.parametrize("fixture_path", valid_fixtures, ids=lambda p: p.name)
def test_valid_fixture_passes_schema(fixture_path):
    schema_key = fixture_to_schema_key(fixture_path.name)
    assert schema_key is not None, f"No schema mapping for fixture: {fixture_path.name}"

    schema = load_schema(SCHEMA_MAP[schema_key])
    fixture = load_fixture(fixture_path)

    # Remove _comment fields (used for documentation in fixtures)
    fixture = {k: v for k, v in fixture.items() if k != "_comment"}

    validator = Draft202012Validator(schema)
    errors = list(validator.iter_errors(fixture))
    assert len(errors) == 0, f"Valid fixture failed validation:\n" + "\n".join(
        f"  - {e.message}" for e in errors
    )


# --- Invalid fixtures: must fail validation ---

invalid_fixtures = collect_fixtures("invalid")


@pytest.mark.parametrize("fixture_path", invalid_fixtures, ids=lambda p: p.name)
def test_invalid_fixture_fails_schema(fixture_path):
    schema_key = fixture_to_schema_key(fixture_path.name)
    assert schema_key is not None, f"No schema mapping for fixture: {fixture_path.name}"

    schema = load_schema(SCHEMA_MAP[schema_key])
    fixture = load_fixture(fixture_path)

    # Remove _comment fields
    fixture = {k: v for k, v in fixture.items() if k != "_comment"}

    validator = Draft202012Validator(schema)
    errors = list(validator.iter_errors(fixture))
    assert len(errors) > 0, f"Invalid fixture should have failed validation but passed: {fixture_path.name}"


# --- Schema integrity: all schemas referenced in SCHEMA_MAP exist ---

def test_all_schemas_exist():
    for key, path in SCHEMA_MAP.items():
        full_path = CONTRACTS_DIR / path
        assert full_path.exists(), f"Schema file missing: {full_path}"


def test_all_schemas_are_valid_json():
    for key, path in SCHEMA_MAP.items():
        full_path = CONTRACTS_DIR / path
        with open(full_path) as f:
            schema = json.load(f)
        assert "$schema" in schema, f"Schema {path} missing $schema field"
