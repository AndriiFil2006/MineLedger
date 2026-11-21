"""The Minecraft client's HTTP tests deserialize shared/fixtures/api_responses; the API must return exactly those bodies."""

from __future__ import annotations

import json

import pytest

from mineledger_api.config import REPOSITORY_ROOT

FIXTURES = REPOSITORY_ROOT / "shared" / "fixtures" / "api_responses"
MANIFEST = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))


def test_manifest_lists_every_fixture_file():
    assert MANIFEST["fixture_version"] == 1
    listed = {entry["file"] for entry in MANIFEST["responses"]}
    assert listed == {path.name for path in FIXTURES.glob("*.json")} - {"manifest.json"}


@pytest.mark.parametrize("entry", MANIFEST["responses"], ids=lambda entry: entry["path"])
def test_api_returns_the_shared_fixture(client, entry):
    response = client.get(entry["path"])
    assert response.status_code == 200
    assert response.json() == json.loads((FIXTURES / entry["file"]).read_text(encoding="utf-8"))
