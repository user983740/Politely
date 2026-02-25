from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.rag import RagEntry


async def find_all_enabled(session: AsyncSession) -> list[RagEntry]:
    result = await session.execute(select(RagEntry).where(RagEntry.enabled.is_(True)))
    return list(result.scalars().all())


async def count_by_category(session: AsyncSession) -> dict[str, int]:
    result = await session.execute(
        select(RagEntry.category, func.count(RagEntry.id))
        .where(RagEntry.enabled.is_(True))
        .group_by(RagEntry.category)
    )
    return dict(result.all())


async def find_by_dedupe_key(session: AsyncSession, dedupe_key: str) -> RagEntry | None:
    result = await session.execute(select(RagEntry).where(RagEntry.dedupe_key == dedupe_key))
    return result.scalar_one_or_none()


async def save_entry(session: AsyncSession, entry: RagEntry) -> RagEntry:
    session.add(entry)
    await session.flush()
    return entry


async def save_batch(session: AsyncSession, entries: list[RagEntry]) -> int:
    for entry in entries:
        session.add(entry)
    await session.flush()
    return len(entries)


async def delete_all(session: AsyncSession) -> int:
    result = await session.execute(delete(RagEntry))
    await session.flush()
    return result.rowcount  # type: ignore[return-value]


async def delete_by_category(session: AsyncSession, category: str) -> int:
    result = await session.execute(delete(RagEntry).where(RagEntry.category == category))
    await session.flush()
    return result.rowcount  # type: ignore[return-value]
