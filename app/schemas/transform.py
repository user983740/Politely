from pydantic import BaseModel, Field


class TransformResponse(BaseModel):
    transformed_text: str = Field(..., alias="transformedText", serialization_alias="transformedText")
    analysis_context: str | None = Field(None, alias="analysisContext", serialization_alias="analysisContext")

    model_config = {"populate_by_name": True}


class TransformTextOnlyRequest(BaseModel):
    original_text: str = Field(..., alias="originalText", min_length=1, max_length=2000)
    sender_info: str | None = Field(None, alias="senderInfo", max_length=100)
    user_prompt: str | None = Field(None, alias="userPrompt", max_length=500)

    model_config = {"populate_by_name": True}


class TierInfoResponse(BaseModel):
    tier: str
    max_text_length: int = Field(..., alias="maxTextLength", serialization_alias="maxTextLength")
    prompt_enabled: bool = Field(..., alias="promptEnabled", serialization_alias="promptEnabled")

    model_config = {"populate_by_name": True}
