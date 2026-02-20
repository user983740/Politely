"""Tests for core security utilities (password hashing, JWT)."""

from datetime import datetime, timedelta, timezone

from jose import jwt

from app.core.config import settings
from app.core.security import (
    ALGORITHM,
    generate_token,
    get_email_from_token,
    hash_password,
    validate_token,
    verify_password,
)


def test_hash_and_verify_password_roundtrip():
    hashed = hash_password("MyPass123!")
    assert verify_password("MyPass123!", hashed)


def test_verify_password_rejects_wrong():
    hashed = hash_password("CorrectPass1!")
    assert not verify_password("WrongPass1!", hashed)


def test_generate_token_returns_string():
    token = generate_token("user@example.com")
    assert isinstance(token, str)
    assert len(token) > 0


def test_validate_token_accepts_valid():
    token = generate_token("user@example.com")
    assert validate_token(token) is True


def test_validate_token_rejects_expired():
    payload = {
        "sub": "user@example.com",
        "iat": datetime.now(timezone.utc) - timedelta(hours=48),
        "exp": datetime.now(timezone.utc) - timedelta(hours=24),
    }
    token = jwt.encode(payload, settings.jwt_secret, algorithm=ALGORITHM)
    assert validate_token(token) is False


def test_validate_token_rejects_invalid():
    assert validate_token("not.a.valid.jwt") is False


def test_get_email_from_token_extracts_email():
    email = "alice@example.com"
    token = generate_token(email)
    assert get_email_from_token(token) == email
