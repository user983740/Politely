from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.responses import JSONResponse

from app.api.v1.deps import get_db
from app.schemas.auth import (
    AuthResponse,
    CheckLoginIdRequest,
    LoginRequest,
    SendVerificationRequest,
    SignupRequest,
    VerifyCodeRequest,
)
from app.services import auth_service, email_verification_service

router = APIRouter(prefix="/api/auth", tags=["auth"])


@router.post("/email/send-code")
async def send_verification_code(
    request: SendVerificationRequest,
    db: AsyncSession = Depends(get_db),
):
    await email_verification_service.send_verification_code(db, request.email)
    return {"message": "인증코드가 발송되었습니다."}


@router.post("/email/verify-code")
async def verify_code(
    request: VerifyCodeRequest,
    db: AsyncSession = Depends(get_db),
):
    await email_verification_service.verify_code(db, request.email, request.code)
    return {"message": "이메일 인증이 완료되었습니다."}


@router.post("/check-login-id")
async def check_login_id(
    request: CheckLoginIdRequest,
    db: AsyncSession = Depends(get_db),
):
    available = await auth_service.check_login_id_availability(db, request.login_id)
    return {"available": available}


@router.post("/signup", response_model=AuthResponse, status_code=201)
async def signup(
    request: SignupRequest,
    db: AsyncSession = Depends(get_db),
):
    result = await auth_service.signup(
        db, request.email, request.login_id, request.name, request.password
    )
    return result


@router.post("/login", response_model=AuthResponse)
async def login(
    request: LoginRequest,
    db: AsyncSession = Depends(get_db),
):
    result = await auth_service.login(db, request.email, request.password)
    return result
