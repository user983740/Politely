import logging

import resend

from app.core.config import settings

logger = logging.getLogger(__name__)


class ResendEmailService:
    def __init__(self):
        resend.api_key = settings.resend_api_key

    def send_verification_email(self, to: str, code: str) -> None:
        subject = "[Politely] 이메일 인증코드"
        body = (
            f"안녕하세요, Politely입니다.\n\n"
            f"이메일 인증코드: {code}\n\n"
            f"인증코드는 5분간 유효합니다.\n"
            f"본인이 요청하지 않은 경우 이 메일을 무시해주세요."
        )

        try:
            resend.Emails.send(
                {
                    "from": settings.app_email_sender,
                    "to": to,
                    "subject": subject,
                    "text": body,
                }
            )
            logger.info("Verification email sent to %s", to)
        except Exception as e:
            logger.error("Failed to send verification email to %s: %s", to, e)
            raise RuntimeError("이메일 발송에 실패했습니다.") from e
