"""Integration tests for auth API endpoints."""

from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import EmailVerification

# --- send-code ---


@pytest.mark.asyncio
async def test_send_code_success(client, test_session: AsyncSession):
    with patch(
        "app.services.email_verification_service._get_email_service"
    ) as mock_svc:
        mock_svc.return_value.send_verification_email = lambda *a: None
        resp = await client.post(
            "/api/auth/email/send-code",
            json={"email": "new@example.com"},
        )
    assert resp.status_code == 200
    assert "인증코드" in resp.json()["message"]


@pytest.mark.asyncio
async def test_send_code_duplicate_email(client, created_user):
    resp = await client.post(
        "/api/auth/email/send-code",
        json={"email": created_user.email},
    )
    assert resp.status_code == 409
    assert resp.json()["error"] == "DUPLICATE_EMAIL"


# --- verify-code ---


@pytest.mark.asyncio
async def test_verify_code_success(client, test_session: AsyncSession):
    now = datetime.now(timezone.utc)
    v = EmailVerification(
        email="verify@example.com", code="654321", verified=False,
        expires_at=now + timedelta(minutes=5), created_at=now,
    )
    test_session.add(v)
    await test_session.commit()

    resp = await client.post(
        "/api/auth/email/verify-code",
        json={"email": "verify@example.com", "code": "654321"},
    )
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_verify_code_wrong_code(client, test_session: AsyncSession):
    now = datetime.now(timezone.utc)
    v = EmailVerification(
        email="wrong@example.com", code="111111", verified=False,
        expires_at=now + timedelta(minutes=5), created_at=now,
    )
    test_session.add(v)
    await test_session.commit()

    resp = await client.post(
        "/api/auth/email/verify-code",
        json={"email": "wrong@example.com", "code": "999999"},
    )
    assert resp.status_code == 400
    assert resp.json()["error"] == "INVALID_VERIFICATION_CODE"


@pytest.mark.asyncio
async def test_verify_code_expired(client, test_session: AsyncSession):
    past = datetime.now(timezone.utc) - timedelta(minutes=10)
    v = EmailVerification(
        email="expired@example.com", code="222222", verified=False,
        expires_at=past, created_at=past - timedelta(minutes=5),
    )
    test_session.add(v)
    await test_session.commit()

    resp = await client.post(
        "/api/auth/email/verify-code",
        json={"email": "expired@example.com", "code": "222222"},
    )
    assert resp.status_code == 400
    assert resp.json()["error"] == "VERIFICATION_EXPIRED"


@pytest.mark.asyncio
async def test_verify_code_not_found(client):
    resp = await client.post(
        "/api/auth/email/verify-code",
        json={"email": "nobody@example.com", "code": "000000"},
    )
    assert resp.status_code == 404
    assert resp.json()["error"] == "VERIFICATION_NOT_FOUND"


# --- check-login-id ---


@pytest.mark.asyncio
async def test_check_login_id_available(client):
    resp = await client.post(
        "/api/auth/check-login-id",
        json={"loginId": "brandnew"},
    )
    assert resp.status_code == 200
    assert resp.json()["available"] is True


@pytest.mark.asyncio
async def test_check_login_id_taken(client, created_user):
    resp = await client.post(
        "/api/auth/check-login-id",
        json={"loginId": created_user.login_id},
    )
    assert resp.status_code == 200
    assert resp.json()["available"] is False


# --- signup ---


@pytest.mark.asyncio
async def test_signup_success(client, verified_email_session):
    resp = await client.post(
        "/api/auth/signup",
        json={
            "email": "newuser@example.com",
            "loginId": "newlogin",
            "name": "New User",
            "password": "StrongP@ss1",
            "privacyAgreed": True,
        },
    )
    assert resp.status_code == 201
    body = resp.json()
    assert "token" in body
    assert body["email"] == "newuser@example.com"
    assert body["loginId"] == "newlogin"


@pytest.mark.asyncio
async def test_signup_duplicate_email(client, created_user, verified_email_session):
    # Add verified email for the existing user's email
    now = datetime.now(timezone.utc)
    v = EmailVerification(
        email=created_user.email, code="111111", verified=True,
        expires_at=now + timedelta(minutes=5), created_at=now,
    )
    verified_email_session.add(v)
    await verified_email_session.commit()

    resp = await client.post(
        "/api/auth/signup",
        json={
            "email": created_user.email,
            "loginId": "anotherlogin",
            "name": "Dup",
            "password": "StrongP@ss1",
            "privacyAgreed": True,
        },
    )
    assert resp.status_code == 409
    assert resp.json()["error"] == "DUPLICATE_EMAIL"


@pytest.mark.asyncio
async def test_signup_email_not_verified(client):
    resp = await client.post(
        "/api/auth/signup",
        json={
            "email": "unverified@example.com",
            "loginId": "unvlogin",
            "name": "Unv",
            "password": "StrongP@ss1",
            "privacyAgreed": True,
        },
    )
    assert resp.status_code == 400
    assert resp.json()["error"] == "EMAIL_NOT_VERIFIED"


# --- login ---


@pytest.mark.asyncio
async def test_login_success(client, created_user):
    resp = await client.post(
        "/api/auth/login",
        json={"email": "test@example.com", "password": "Test1234!"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert "token" in body
    assert body["email"] == "test@example.com"


@pytest.mark.asyncio
async def test_login_wrong_credentials(client, created_user):
    resp = await client.post(
        "/api/auth/login",
        json={"email": "test@example.com", "password": "WrongPass1!"},
    )
    assert resp.status_code == 401
    assert resp.json()["error"] == "INVALID_CREDENTIALS"
