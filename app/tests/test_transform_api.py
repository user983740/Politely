"""Integration tests for transform API endpoints."""

from unittest.mock import AsyncMock, patch

import pytest

from app.models.domain import TransformResult


@pytest.mark.asyncio
async def test_get_tier_info(client):
    resp = await client.get("/api/v1/transform/tier")
    assert resp.status_code == 200
    body = resp.json()
    assert body["tier"] == "PAID"
    assert body["maxTextLength"] == 2000
    assert body["promptEnabled"] is True


@pytest.mark.asyncio
async def test_transform_success(client):
    mock_result = TransformResult(transformed_text="변환된 텍스트입니다.")
    with patch(
        "app.services.transform_app_service.transform",
        new_callable=AsyncMock,
        return_value=mock_result,
    ):
        resp = await client.post(
            "/api/v1/transform",
            json={
                "persona": "BOSS",
                "contexts": ["REQUEST"],
                "toneLevel": "POLITE",
                "originalText": "내일까지 보고서 보내주세요",
            },
        )
    assert resp.status_code == 200
    body = resp.json()
    assert body["transformedText"] == "변환된 텍스트입니다."


@pytest.mark.asyncio
async def test_transform_empty_text_422(client):
    resp = await client.post(
        "/api/v1/transform",
        json={
            "persona": "BOSS",
            "contexts": ["REQUEST"],
            "toneLevel": "POLITE",
            "originalText": "",
        },
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_transform_text_too_long_400(client):
    with patch(
        "app.services.transform_app_service.transform",
        new_callable=AsyncMock,
        side_effect=ValueError("최대 2000자까지 입력할 수 있습니다."),
    ):
        resp = await client.post(
            "/api/v1/transform",
            json={
                "persona": "BOSS",
                "contexts": ["REQUEST"],
                "toneLevel": "POLITE",
                "originalText": "x" * 2000,  # within schema limit, service rejects
            },
        )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_transform_pipeline_error_503(client):
    from app.core.exceptions import AiTransformError

    with patch(
        "app.services.transform_app_service.transform",
        new_callable=AsyncMock,
        side_effect=AiTransformError("Pipeline failure"),
    ):
        resp = await client.post(
            "/api/v1/transform",
            json={
                "persona": "BOSS",
                "contexts": ["REQUEST"],
                "toneLevel": "POLITE",
                "originalText": "테스트 텍스트입니다",
            },
        )
    assert resp.status_code == 503
    assert resp.json()["error"] == "AI_TRANSFORM_ERROR"
