from datetime import datetime

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import EmailVerification, User


# --- User Repository ---


async def find_user_by_email(session: AsyncSession, email: str) -> User | None:
    result = await session.execute(select(User).where(User.email == email))
    return result.scalar_one_or_none()


async def exists_user_by_email(session: AsyncSession, email: str) -> bool:
    result = await session.execute(select(User.id).where(User.email == email))
    return result.scalar_one_or_none() is not None


async def exists_user_by_login_id(session: AsyncSession, login_id: str) -> bool:
    result = await session.execute(select(User.id).where(User.login_id == login_id))
    return result.scalar_one_or_none() is not None


async def save_user(session: AsyncSession, user: User) -> User:
    session.add(user)
    await session.flush()
    return user


# --- EmailVerification Repository ---


async def find_latest_verification_by_email(session: AsyncSession, email: str) -> EmailVerification | None:
    result = await session.execute(
        select(EmailVerification)
        .where(EmailVerification.email == email)
        .order_by(EmailVerification.created_at.desc())
        .limit(1)
    )
    return result.scalar_one_or_none()


async def save_verification(session: AsyncSession, verification: EmailVerification) -> EmailVerification:
    session.add(verification)
    await session.flush()
    return verification


async def delete_expired_verifications(session: AsyncSession) -> None:
    await session.execute(delete(EmailVerification).where(EmailVerification.expires_at < datetime.utcnow()))
    await session.commit()
