import hashlib
from datetime import datetime
from enum import Enum

from sqlalchemy import Boolean, DateTime, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.models.user import Base


class RagCategory(str, Enum):
    EXPRESSION_POOL = "expression_pool"
    CUSHION = "cushion"
    FORBIDDEN = "forbidden"
    POLICY = "policy"
    EXAMPLE = "example"
    DOMAIN_CONTEXT = "domain_context"


class RagEntry(Base):
    __tablename__ = "rag_entries"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    category: Mapped[str] = mapped_column(String(30), nullable=False, index=True)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    original_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    alternative: Mapped[str | None] = mapped_column(Text, nullable=True)
    trigger_phrases: Mapped[str | None] = mapped_column(Text, nullable=True)
    dedupe_key: Mapped[str | None] = mapped_column(String(64), nullable=True, unique=True)

    # Metadata filters (CSV, NULL = match all)
    personas: Mapped[str | None] = mapped_column(String(200), nullable=True)
    contexts: Mapped[str | None] = mapped_column(String(500), nullable=True)
    tone_levels: Mapped[str | None] = mapped_column(String(100), nullable=True)
    sections: Mapped[str | None] = mapped_column(String(100), nullable=True)
    yellow_labels: Mapped[str | None] = mapped_column(String(200), nullable=True)

    embedding_blob: Mapped[str | None] = mapped_column(Text, nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow
    )


def parse_csv_filter(value: str | None) -> frozenset[str]:
    """Parse CSV string → frozenset[str]. Upper/strip/dedupe. NULL → empty frozenset."""
    if not value:
        return frozenset()
    return frozenset(v.strip().upper() for v in value.split(",") if v.strip())


def compute_dedupe_key(category: str, content: str, personas: str | None, contexts: str | None) -> str:
    """SHA-256 hash of category|content|personas|contexts for idempotent upsert."""
    raw = f"{category}|{content}|{personas or ''}|{contexts or ''}"
    return hashlib.sha256(raw.encode()).hexdigest()
