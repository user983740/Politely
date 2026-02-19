import asyncio
import logging

from app.db.repositories import delete_expired_verifications
from app.db.session import async_session_factory

logger = logging.getLogger(__name__)

CLEANUP_INTERVAL_SECONDS = 3600  # 1 hour


async def start_cleanup_scheduler():
    while True:
        try:
            await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
            async with async_session_factory() as session:
                await delete_expired_verifications(session)
            logger.debug("Cleaned up expired email verification records")
        except asyncio.CancelledError:
            break
        except Exception:
            logger.exception("Error during verification cleanup")
