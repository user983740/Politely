import secrets
from datetime import datetime, timedelta, timezone

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.exceptions import (
    DuplicateEmailException,
    InvalidVerificationCodeException,
    VerificationExpiredException,
    VerificationNotFoundException,
)
from app.db.repositories import (
    exists_user_by_email,
    find_latest_verification_by_email,
    save_verification,
)
from app.email.console_email_service import ConsoleEmailService
from app.email.resend_email_service import ResendEmailService
from app.models.user import EmailVerification

CODE_LENGTH = 6
EXPIRATION_MINUTES = 5


def _get_email_service():
    if settings.environment == "prod":
        return ResendEmailService()
    return ConsoleEmailService()


def _generate_code() -> str:
    number = secrets.randbelow(900_000) + 100_000
    return str(number)


async def send_verification_code(session: AsyncSession, email: str) -> None:
    if await exists_user_by_email(session, email):
        raise DuplicateEmailException()

    code = _generate_code()
    now = datetime.now(timezone.utc)
    verification = EmailVerification(
        email=email,
        code=code,
        verified=False,
        expires_at=now + timedelta(minutes=EXPIRATION_MINUTES),
        created_at=now,
    )
    await save_verification(session, verification)
    await session.commit()

    email_service = _get_email_service()
    email_service.send_verification_email(email, code)


async def verify_code(session: AsyncSession, email: str, code: str) -> None:
    verification = await find_latest_verification_by_email(session, email)
    if verification is None:
        raise VerificationNotFoundException()

    if verification.is_expired():
        raise VerificationExpiredException()

    if verification.code != code:
        raise InvalidVerificationCodeException()

    verification.mark_verified()
    await session.commit()
