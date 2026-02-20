"""Tests for database repository functions."""

from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.repositories import (
    delete_expired_verifications,
    exists_user_by_email,
    exists_user_by_login_id,
    find_latest_verification_by_email,
    find_user_by_email,
    save_user,
    save_verification,
)
from app.models.user import EmailVerification, User


@pytest.mark.asyncio
async def test_save_and_find_user_by_email(test_session: AsyncSession):
    user = User(
        email="repo@test.com", login_id="repoid",
        name="Repo", password="hashed",
    )
    await save_user(test_session, user)
    await test_session.commit()

    found = await find_user_by_email(test_session, "repo@test.com")
    assert found is not None
    assert found.login_id == "repoid"


@pytest.mark.asyncio
async def test_find_user_by_email_not_found(test_session: AsyncSession):
    found = await find_user_by_email(test_session, "nonexistent@test.com")
    assert found is None


@pytest.mark.asyncio
async def test_exists_user_by_email(test_session: AsyncSession):
    assert await exists_user_by_email(test_session, "nope@test.com") is False
    user = User(
        email="exists@test.com", login_id="existsid",
        name="Ex", password="hashed",
    )
    await save_user(test_session, user)
    await test_session.commit()
    assert await exists_user_by_email(test_session, "exists@test.com") is True


@pytest.mark.asyncio
async def test_exists_user_by_login_id(test_session: AsyncSession):
    assert await exists_user_by_login_id(test_session, "noid") is False
    user = User(
        email="lid@test.com", login_id="myloginid",
        name="LI", password="hashed",
    )
    await save_user(test_session, user)
    await test_session.commit()
    assert await exists_user_by_login_id(test_session, "myloginid") is True


@pytest.mark.asyncio
async def test_save_and_find_latest_verification(test_session: AsyncSession):
    now = datetime.now(timezone.utc)
    v = EmailVerification(
        email="v@test.com", code="111111", verified=False,
        expires_at=now + timedelta(minutes=5), created_at=now,
    )
    await save_verification(test_session, v)
    await test_session.commit()

    found = await find_latest_verification_by_email(test_session, "v@test.com")
    assert found is not None
    assert found.code == "111111"


@pytest.mark.asyncio
async def test_find_latest_verification_returns_most_recent(test_session: AsyncSession):
    now = datetime.now(timezone.utc)
    old = EmailVerification(
        email="multi@test.com", code="000000", verified=False,
        expires_at=now + timedelta(minutes=5),
        created_at=now - timedelta(minutes=10),
    )
    new = EmailVerification(
        email="multi@test.com", code="999999", verified=False,
        expires_at=now + timedelta(minutes=5), created_at=now,
    )
    await save_verification(test_session, old)
    await save_verification(test_session, new)
    await test_session.commit()

    found = await find_latest_verification_by_email(test_session, "multi@test.com")
    assert found is not None
    assert found.code == "999999"


@pytest.mark.asyncio
async def test_find_latest_verification_not_found(test_session: AsyncSession):
    found = await find_latest_verification_by_email(test_session, "nobody@test.com")
    assert found is None


@pytest.mark.asyncio
async def test_delete_expired_verifications(test_session: AsyncSession):
    now = datetime.utcnow()
    expired = EmailVerification(
        email="exp@test.com", code="111111", verified=False,
        expires_at=now - timedelta(minutes=10),
        created_at=now - timedelta(minutes=15),
    )
    valid = EmailVerification(
        email="val@test.com", code="222222", verified=False,
        expires_at=now + timedelta(minutes=5), created_at=now,
    )
    await save_verification(test_session, expired)
    await save_verification(test_session, valid)
    await test_session.commit()

    await delete_expired_verifications(test_session)

    assert await find_latest_verification_by_email(test_session, "exp@test.com") is None
    assert await find_latest_verification_by_email(test_session, "val@test.com") is not None
