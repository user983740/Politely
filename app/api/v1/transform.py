from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from sse_starlette.sse import EventSourceResponse

from app.api.v1.deps import get_db, get_current_user_optional
from app.models.user import User
from app.schemas.transform import TierInfoResponse, TransformRequest, TransformResponse
from app.services import transform_app_service

router = APIRouter(prefix="/api/v1/transform", tags=["transform"])


@router.post("", response_model=TransformResponse)
async def transform(
    request: TransformRequest,
    db: AsyncSession = Depends(get_db),
):
    result = await transform_app_service.transform(
        request.persona,
        request.contexts,
        request.tone_level,
        request.original_text,
        request.user_prompt,
        request.sender_info,
    )
    return TransformResponse(
        transformed_text=result.transformed_text,
        analysis_context=result.analysis_context,
    )


@router.post("/stream")
async def stream_transform(
    request: TransformRequest,
    db: AsyncSession = Depends(get_db),
):
    transform_app_service.validate_transform_request(request.original_text)

    from app.pipeline.ai_streaming_service import stream_transform as do_stream

    return EventSourceResponse(
        do_stream(
            request.persona,
            request.contexts,
            request.tone_level,
            request.original_text,
            request.user_prompt,
            request.sender_info,
            bool(request.identity_booster_toggle),
            request.topic,
            request.purpose,
            transform_app_service.resolve_final_max_tokens(),
        )
    )


@router.get("/tier", response_model=TierInfoResponse)
async def get_tier_info(
    user: User | None = Depends(get_current_user_optional),
):
    max_text_length = transform_app_service.get_max_text_length()
    return TierInfoResponse(
        tier="PAID",
        max_text_length=max_text_length,
        prompt_enabled=True,
    )
