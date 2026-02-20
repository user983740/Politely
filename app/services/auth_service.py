from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import (
    DuplicateEmailError,
    DuplicateLoginIdError,
    EmailNotVerifiedError,
    InvalidCredentialsError,
)
from app.core.security import generate_token, hash_password, verify_password
from app.db.repositories import (
    exists_user_by_email,
    exists_user_by_login_id,
    find_latest_verification_by_email,
    find_user_by_email,
    save_user,
)
from app.models.user import User
from app.schemas.auth import AuthResponse


async def signup(
    session: AsyncSession,
    email: str,
    login_id: str,
    name: str,
    password: str,
) -> AuthResponse:
    if await exists_user_by_email(session, email):
        raise DuplicateEmailError()

    if await exists_user_by_login_id(session, login_id):
        raise DuplicateLoginIdError()

    verification = await find_latest_verification_by_email(session, email)
    if verification is None or not verification.verified:
        raise EmailNotVerifiedError()

    user = User(
        email=email,
        login_id=login_id,
        name=name,
        password=hash_password(password),
    )
    await save_user(session, user)
    await session.commit()

    token = generate_token(user.email)
    return AuthResponse(token=token, email=user.email, login_id=user.login_id, name=user.name)


async def login(session: AsyncSession, email: str, password: str) -> AuthResponse:
    user = await find_user_by_email(session, email)
    if user is None:
        raise InvalidCredentialsError()

    if not verify_password(password, user.password):
        raise InvalidCredentialsError()

    token = generate_token(user.email)
    return AuthResponse(token=token, email=user.email, login_id=user.login_id, name=user.name)


async def check_login_id_availability(session: AsyncSession, login_id: str) -> bool:
    return not await exists_user_by_login_id(session, login_id)
