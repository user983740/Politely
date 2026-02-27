from fastapi import APIRouter, Depends
from sse_starlette.sse import EventSourceResponse

from app.api.v1.deps import get_current_user_optional
from app.models.user import User
from app.schemas.transform import TierInfoResponse, TransformResponse, TransformTextOnlyRequest
from app.services import transform_app_service

router = APIRouter(prefix="/api/v1/transform", tags=["transform"])


@router.post("", response_model=TransformResponse)
async def transform(
    request: TransformTextOnlyRequest,
):
    from app.pipeline import text_only_pipeline

    transform_app_service.validate_transform_request(request.original_text)
    result = await text_only_pipeline.execute(
        request.original_text, request.sender_info, request.user_prompt,
    )
    return TransformResponse(transformed_text=result.transformed_text)


@router.post("/stream")
async def stream_transform(
    request: TransformTextOnlyRequest,
):
    transform_app_service.validate_transform_request(request.original_text)

    from app.pipeline.ai_streaming_service import stream_text_only

    return EventSourceResponse(
        stream_text_only(
            request.original_text,
            request.sender_info,
            request.user_prompt,
            transform_app_service.resolve_final_max_tokens(),
        )
    )


@router.post("/stream-ab")
async def stream_transform_ab(
    request: TransformTextOnlyRequest,
):
    transform_app_service.validate_transform_request(request.original_text)

    from app.pipeline.ai_streaming_service import stream_text_only_ab

    return EventSourceResponse(
        stream_text_only_ab(
            request.original_text,
            request.sender_info,
            request.user_prompt,
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
