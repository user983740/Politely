import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

logger = logging.getLogger(__name__)


# --- Custom Exceptions ---


class DuplicateEmailError(Exception):
    def __init__(self):
        super().__init__("이미 사용 중인 이메일입니다.")


class DuplicateLoginIdError(Exception):
    def __init__(self):
        super().__init__("이미 사용 중인 아이디입니다.")


class InvalidVerificationCodeError(Exception):
    def __init__(self):
        super().__init__("인증코드가 올바르지 않습니다.")


class VerificationExpiredError(Exception):
    def __init__(self):
        super().__init__("인증코드가 만료되었습니다. 다시 발송해주세요.")


class EmailNotVerifiedError(Exception):
    def __init__(self):
        super().__init__("이메일 인증이 완료되지 않았습니다.")


class VerificationNotFoundError(Exception):
    def __init__(self):
        super().__init__("인증 요청 내역이 없습니다.")


class InvalidPasswordFormatError(Exception):
    def __init__(self):
        super().__init__("비밀번호는 영문, 숫자, 특수문자를 모두 포함하여 8자 이상이어야 합니다.")


class InvalidCredentialsError(Exception):
    def __init__(self):
        super().__init__("이메일 또는 비밀번호가 올바르지 않습니다.")


class TierRestrictionError(Exception):
    pass


class AiTransformError(Exception):
    pass


# --- Exception Handlers ---


def _error_response(status_code: int, error: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"error": error, "message": message})


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(DuplicateEmailError)
    async def handle_duplicate_email(request: Request, exc: DuplicateEmailError):
        return _error_response(409, "DUPLICATE_EMAIL", str(exc))

    @app.exception_handler(DuplicateLoginIdError)
    async def handle_duplicate_login_id(request: Request, exc: DuplicateLoginIdError):
        return _error_response(409, "DUPLICATE_LOGIN_ID", str(exc))

    @app.exception_handler(InvalidVerificationCodeError)
    async def handle_invalid_code(request: Request, exc: InvalidVerificationCodeError):
        return _error_response(400, "INVALID_VERIFICATION_CODE", str(exc))

    @app.exception_handler(VerificationExpiredError)
    async def handle_expired(request: Request, exc: VerificationExpiredError):
        return _error_response(400, "VERIFICATION_EXPIRED", str(exc))

    @app.exception_handler(EmailNotVerifiedError)
    async def handle_not_verified(request: Request, exc: EmailNotVerifiedError):
        return _error_response(400, "EMAIL_NOT_VERIFIED", str(exc))

    @app.exception_handler(VerificationNotFoundError)
    async def handle_not_found(request: Request, exc: VerificationNotFoundError):
        return _error_response(404, "VERIFICATION_NOT_FOUND", str(exc))

    @app.exception_handler(InvalidPasswordFormatError)
    async def handle_invalid_password(request: Request, exc: InvalidPasswordFormatError):
        return _error_response(400, "INVALID_PASSWORD_FORMAT", str(exc))

    @app.exception_handler(InvalidCredentialsError)
    async def handle_invalid_credentials(request: Request, exc: InvalidCredentialsError):
        return _error_response(401, "INVALID_CREDENTIALS", str(exc))

    @app.exception_handler(TierRestrictionError)
    async def handle_tier_restriction(request: Request, exc: TierRestrictionError):
        return _error_response(403, "TIER_RESTRICTION", str(exc))

    @app.exception_handler(AiTransformError)
    async def handle_ai_transform(request: Request, exc: AiTransformError):
        return _error_response(503, "AI_TRANSFORM_ERROR", str(exc))

    @app.exception_handler(ValueError)
    async def handle_value_error(request: Request, exc: ValueError):
        return _error_response(400, "VALIDATION_ERROR", str(exc))

    @app.exception_handler(ValidationError)
    async def handle_pydantic_validation(request: Request, exc: ValidationError):
        first_error = exc.errors()[0] if exc.errors() else None
        default_msg = "입력값이 올바르지 않습니다."
        message = first_error.get("msg", default_msg) if first_error else default_msg
        return _error_response(400, "VALIDATION_ERROR", message)

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception):
        logger.error("[GlobalExceptionHandler] Unhandled exception", exc_info=exc)
        return _error_response(500, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
