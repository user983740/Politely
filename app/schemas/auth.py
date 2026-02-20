import re

from pydantic import BaseModel, EmailStr, Field, field_validator


class SendVerificationRequest(BaseModel):
    email: EmailStr = Field(..., description="이메일을 입력해주세요")


class VerifyCodeRequest(BaseModel):
    email: EmailStr = Field(..., description="이메일을 입력해주세요")
    code: str = Field(..., min_length=6, max_length=6, description="인증코드는 6자리입니다")


class CheckLoginIdRequest(BaseModel):
    login_id: str = Field(
        ..., alias="loginId", min_length=3, max_length=30,
        description="아이디는 3자 이상 30자 이하로 입력해주세요",
    )

    model_config = {"populate_by_name": True}


class SignupRequest(BaseModel):
    email: EmailStr = Field(..., description="이메일을 입력해주세요")
    login_id: str = Field(
        ..., alias="loginId", min_length=3, max_length=30,
        description="아이디는 3자 이상 30자 이하로 입력해주세요",
    )
    name: str = Field(..., max_length=50, description="이름은 50자 이하로 입력해주세요")
    password: str = Field(..., description="비밀번호를 입력해주세요")
    privacy_agreed: bool = Field(..., alias="privacyAgreed")

    model_config = {"populate_by_name": True}

    @field_validator("password")
    @classmethod
    def validate_password(cls, v: str) -> str:
        pattern = r"^(?=.*[a-zA-Z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=]).{8,}$"
        if not re.match(pattern, v):
            raise ValueError("비밀번호는 영문, 숫자, 특수문자를 모두 포함하여 8자 이상이어야 합니다")
        return v


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class AuthResponse(BaseModel):
    token: str
    email: str
    login_id: str = Field(..., alias="loginId", serialization_alias="loginId")
    name: str

    model_config = {"populate_by_name": True}
