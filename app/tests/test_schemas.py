"""Tests for Pydantic schema validation."""

import pytest
from pydantic import ValidationError

from app.schemas.auth import AuthResponse, CheckLoginIdRequest, SignupRequest, VerifyCodeRequest
from app.schemas.transform import TierInfoResponse, TransformTextOnlyRequest

# --- SignupRequest password validation ---


def test_signup_rejects_password_no_special_char():
    with pytest.raises(ValidationError, match="비밀번호"):
        SignupRequest(
            email="a@b.com", login_id="usr", name="N",
            password="Abcdef12", privacy_agreed=True,
        )


def test_signup_rejects_password_too_short():
    with pytest.raises(ValidationError, match="비밀번호"):
        SignupRequest(
            email="a@b.com", login_id="usr", name="N",
            password="Ab1!", privacy_agreed=True,
        )


def test_signup_rejects_password_no_digit():
    with pytest.raises(ValidationError, match="비밀번호"):
        SignupRequest(
            email="a@b.com", login_id="usr", name="N",
            password="Abcdefgh!", privacy_agreed=True,
        )


def test_signup_accepts_valid_password():
    req = SignupRequest(
        email="a@b.com", login_id="usr", name="Name",
        password="Valid1234!", privacy_agreed=True,
    )
    assert req.password == "Valid1234!"


# --- VerifyCodeRequest ---


def test_verify_code_rejects_short_code():
    with pytest.raises(ValidationError):
        VerifyCodeRequest(email="a@b.com", code="123")


def test_verify_code_rejects_long_code():
    with pytest.raises(ValidationError):
        VerifyCodeRequest(email="a@b.com", code="1234567")


# --- CheckLoginIdRequest ---


def test_check_login_id_rejects_too_short():
    with pytest.raises(ValidationError):
        CheckLoginIdRequest(login_id="ab")


def test_check_login_id_rejects_too_long():
    with pytest.raises(ValidationError):
        CheckLoginIdRequest(login_id="a" * 31)


# --- TransformTextOnlyRequest ---


def test_text_only_request_rejects_empty_text():
    with pytest.raises(ValidationError):
        TransformTextOnlyRequest(original_text="")


def test_text_only_request_rejects_too_long_text():
    with pytest.raises(ValidationError):
        TransformTextOnlyRequest(original_text="x" * 2001)


def test_text_only_request_accepts_valid():
    req = TransformTextOnlyRequest(original_text="안녕하세요")
    assert req.original_text == "안녕하세요"


# --- Serialization aliases ---


def test_auth_response_serializes_camel_case():
    resp = AuthResponse(token="t", email="e@e.com", login_id="lid", name="n")
    data = resp.model_dump(by_alias=True)
    assert "loginId" in data
    assert data["loginId"] == "lid"


def test_tier_info_response_serializes_camel_case():
    resp = TierInfoResponse(tier="PAID", max_text_length=2000, prompt_enabled=True)
    data = resp.model_dump(by_alias=True)
    assert "maxTextLength" in data
    assert "promptEnabled" in data
    assert data["maxTextLength"] == 2000
