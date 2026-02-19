from typing import Protocol


class EmailService(Protocol):
    def send_verification_email(self, to: str, code: str) -> None: ...
