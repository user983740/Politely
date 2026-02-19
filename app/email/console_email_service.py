import logging

logger = logging.getLogger(__name__)


class ConsoleEmailService:
    def send_verification_email(self, to: str, code: str) -> None:
        logger.info(
            "\n========================================\n"
            "[DEV] 이메일 인증코드 발송\n"
            "수신: %s\n"
            "인증코드: %s\n"
            "========================================",
            to,
            code,
        )
