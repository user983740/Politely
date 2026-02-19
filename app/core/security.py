from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt
from passlib.context import CryptContext

from app.core.config import settings

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

ALGORITHM = "HS256"


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    return pwd_context.verify(plain_password, hashed_password)


def generate_token(email: str) -> str:
    now = datetime.now(timezone.utc)
    expire = now + timedelta(milliseconds=settings.jwt_expiration_ms)
    payload = {
        "sub": email,
        "iat": now,
        "exp": expire,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=ALGORITHM)


def validate_token(token: str) -> bool:
    try:
        jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM])
        return True
    except JWTError:
        return False


def get_email_from_token(token: str) -> str:
    payload = jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM])
    return payload["sub"]
