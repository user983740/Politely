import logging

from fastapi import APIRouter, Header, HTTPException

from app.db.session import async_session_factory
from app.services.rag_service import rag_index

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/internal/rag", tags=["rag-admin"])


@router.post("/reload")
async def reload_rag_index(x_internal_token: str = Header(...)):
    from app.core.config import settings

    if not settings.rag_admin_token or x_internal_token != settings.rag_admin_token:
        raise HTTPException(status_code=403, detail="Invalid admin token")

    async with async_session_factory() as session:
        count = await rag_index.reload(session)

    from app.db import rag_repository

    async with async_session_factory() as session:
        by_category = await rag_repository.count_by_category(session)

    logger.info("RAG index reloaded via admin: %d entries", count)
    return {"reloaded": count, "by_category": by_category}
