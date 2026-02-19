from collections.abc import AsyncGenerator

from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, settings
from app.core.security import get_email_from_token, validate_token
from app.db.repositories import find_user_by_email
from app.db.session import get_async_session
from app.models.user import User


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async for session in get_async_session():
        yield session


def get_settings() -> Settings:
    return settings


async def get_current_user_optional(
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> User | None:
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        return None

    token = auth_header[len("Bearer "):]
    if not validate_token(token):
        return None

    email = get_email_from_token(token)
    return await find_user_by_email(db, email)
