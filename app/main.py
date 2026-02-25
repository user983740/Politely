import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import settings
from app.db.session import async_session_factory, engine
from app.models.rag import RagEntry  # noqa: F401 — registers table with Base.metadata
from app.models.user import Base

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: create tables
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("Database tables created")

    # Load RAG index if enabled
    if settings.rag_enabled:
        from app.services.rag_service import rag_index

        try:
            async with async_session_factory() as session:
                count = await rag_index.reload(session)
            logger.info("RAG index loaded: %d entries", count)
        except Exception:
            logger.exception("RAG index loading failed — continuing without RAG")
    else:
        logger.info("RAG disabled (RAG_ENABLED=false)")

    # Start cleanup scheduler
    from app.scheduling.cleanup import start_cleanup_scheduler

    cleanup_task = asyncio.create_task(start_cleanup_scheduler())

    yield

    # Shutdown
    cleanup_task.cancel()
    await engine.dispose()


app = FastAPI(
    title="PoliteAi",
    description="Korean tone/politeness transformation service",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "https://politely-ai.com"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Exception handlers
from app.core.exceptions import register_exception_handlers  # noqa: E402

register_exception_handlers(app)

# Routers
from app.api.v1.auth import router as auth_router  # noqa: E402
from app.api.v1.transform import router as transform_router  # noqa: E402

app.include_router(auth_router)
app.include_router(transform_router)

# RAG admin router — only registered when RAG_ADMIN_TOKEN is set
if settings.rag_admin_token:
    from app.api.v1.rag_admin import router as rag_admin_router  # noqa: E402

    app.include_router(rag_admin_router)


@app.get("/api/health")
async def health():
    return {"status": "ok"}
