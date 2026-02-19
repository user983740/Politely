from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # JWT
    jwt_secret: str = "politeai-dev-secret-key-change-in-production-minimum-32-bytes!!"
    jwt_expiration_ms: int = 86400000  # 24 hours

    # Email
    app_email_sender: str = "noreply@politeai.com"

    # OpenAI
    openai_api_key: str = "sk-test-placeholder"
    openai_model: str = "gpt-4o-mini"
    openai_temperature: float = 0.85
    openai_max_tokens: int = 2000
    openai_max_tokens_paid: int = 4000

    # Segmenter
    segmenter_max_segment_length: int = 250
    segmenter_discourse_marker_min_length: int = 150
    segmenter_enumeration_min_length: int = 120

    # Tier
    tier_free_max_text_length: int = 300
    tier_paid_max_text_length: int = 2000

    # Resend
    resend_api_key: str = ""

    # Database
    database_url: str = "sqlite+aiosqlite:///./politeai.db"

    # Environment
    environment: str = "dev"

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8", "extra": "ignore"}


settings = Settings()
