"""Smoke tests for health endpoint."""

import pytest


@pytest.mark.asyncio
async def test_health_returns_ok(client):
    resp = await client.get("/api/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


@pytest.mark.asyncio
async def test_health_response_content_type(client):
    resp = await client.get("/api/health")
    assert "application/json" in resp.headers["content-type"]
