# REST Endpoints & DTOs

## Endpoints

### Auth Router — prefix `/api/auth`

| Method | Path | Handler | Request Body | Response | Status |
|--------|------|---------|-------------|----------|--------|
| POST | `/email/send-code` | `send_verification_code` | `{email: EmailStr}` | `{"message": "인증코드가 발송되었습니다."}` | 200 |
| POST | `/email/verify-code` | `verify_code` | `{email: EmailStr, code: str(6)}` | `{"message": "이메일 인증이 완료되었습니다."}` | 200 |
| POST | `/check-login-id` | `check_login_id` | `{loginId: str(3-30)}` | `{"available": bool}` | 200 |
| POST | `/signup` | `signup` | `SignupRequest` | `AuthResponse` | 201 |
| POST | `/login` | `login` | `LoginRequest` | `AuthResponse` | 200 |

### Transform Router — prefix `/api/v1/transform`

| Method | Path | Handler | Request Body | Response | Status |
|--------|------|---------|-------------|----------|--------|
| POST | `` | `transform` | `TransformTextOnlyRequest` | `TransformResponse` | 200 |
| POST | `/stream` | `stream_transform` | `TransformTextOnlyRequest` | `EventSourceResponse` (SSE) | 200 |
| GET | `/tier` | `get_tier_info` | — | `TierInfoResponse` | 200 |

All transform endpoints use the text-only pipeline (no metadata). No DB dependency.

### Health

| Method | Path | Response |
|--------|------|----------|
| GET | `/api/health` | `{"status": "ok"}` |

## Dependencies (`deps.py`)

| Dependency | Type | Description |
|------------|------|-------------|
| `get_db()` | AsyncGenerator[AsyncSession] | Yields DB session from session factory |
| `get_settings()` | Settings | Returns global settings instance |
| `get_current_user_optional(request, db)` | User \| None | Extracts Bearer JWT → validates → finds user. Returns None if missing/invalid |

**Auth guard pattern:** Endpoints that need auth use `Depends(get_current_user_optional)` and check for None. No mandatory auth guard (all transform endpoints currently work without auth).

## SSE Streaming Events (15 types)

| Event | Data Type | When Emitted | Client Action |
|-------|-----------|-------------|---------------|
| `phase` | string | Pipeline phase change | Update PipelineTracePanel step status |
| `spans` | JSON array | Locked spans extracted | Store in spansRef for placeholder replacement |
| `maskedText` | string | Text after masking | Display in PipelineTracePanel |
| `segments` | JSON array | After segmentation | Display in PipelineTracePanel |
| `labels` | JSON array | After labeling | Display in AnalysisPanel + PipelineTracePanel |
| `situationAnalysis` | JSON object | After situation analysis | Display in PipelineTracePanel |
| `processedSegments` | JSON array | After redaction | Display in PipelineTracePanel |
| `templateSelected` | JSON object | After template selection | Display in PipelineTracePanel |
| `delta` | string | Streaming token from Final LLM | Append to transformedText (with span replacement) |
| `retry` | string | Validation failed, retrying | Client discards accumulated deltas, resets rawStream |
| `validationIssues` | JSON array | After validation | Display in PipelineTracePanel |
| `stats` | JSON object | Pipeline complete | Display in QualityReportPanel |
| `usage` | JSON object | Pipeline complete | Display in CostPanel |
| `done` | string | Final unmasked text | Set final transformedText |
| `error` | string | Error occurred | Display error message |

### Event Data Shapes

```json
// spans
[{"placeholder": "{{DATE_1}}", "original": "2025년 3월 15일", "type": "DATE"}]

// segments
[{"id": "T1", "text": "segment text", "start": 0, "end": 25}]

// labels
[{"segmentId": "T1", "label": "CORE_FACT", "tier": "GREEN", "text": "segment text"}]

// situationAnalysis
{"facts": [{"content": "fact summary", "source": "exact quote"}], "intent": "core purpose"}

// processedSegments
[{"id": "T1", "tier": "GREEN", "label": "CORE_FACT", "text": "text"},
 {"id": "T3", "tier": "RED", "label": "AGGRESSION", "text": null}]

// templateSelected
{"templateId": "T05", "templateName": "사과/수습", "metadataOverridden": false}

// validationIssues
[{"type": "EMOJI", "severity": "ERROR", "message": "이모지가 포함되어 있습니다", "matchedText": "😊"}]

// stats
{"segmentCount": 5, "greenCount": 3, "yellowCount": 1, "redCount": 1,
 "lockedSpanCount": 2, "retryCount": 0, "identityBoosterFired": false,
 "situationAnalysisFired": true, "metadataOverridden": false,
 "chosenTemplateId": "T05", "latencyMs": 3200}

// usage
{"analysisPromptTokens": 1200, "analysisCompletionTokens": 300,
 "finalPromptTokens": 2500, "finalCompletionTokens": 800,
 "totalCostUsd": 0.0023,
 "monthly": {"mvp": 2.30, "growth": 11.50, "mature": 23.00}}
```

## Pydantic Schemas (`app/schemas/`)

### Auth Schemas (`auth.py`)

```python
class SendVerificationRequest:
    email: EmailStr

class VerifyCodeRequest:
    email: EmailStr
    code: str  # min_length=6, max_length=6

class CheckLoginIdRequest:
    login_id: str  # alias="loginId", min_length=3, max_length=30

class SignupRequest:
    email: EmailStr
    login_id: str   # alias="loginId", 3-30 chars
    name: str       # max_length=50
    password: str   # validator: letters + digits + special chars, 8+ length
    privacy_agreed: bool  # alias="privacyAgreed"

class LoginRequest:
    email: EmailStr
    password: str

class AuthResponse:
    token: str
    email: str
    login_id: str   # serialization_alias="loginId"
    name: str
```

### Transform Schemas (`transform.py`)

```python
class TransformTextOnlyRequest:
    original_text: str                 # alias="originalText", 1-2000 chars
    sender_info: str | None            # alias="senderInfo", max 100 chars
    user_prompt: str | None            # alias="userPrompt", max 500 chars

class TransformRequest:                # Legacy, kept for reference
    persona, contexts, tone_level, original_text, user_prompt,
    sender_info, identity_booster_toggle, topic, purpose

class TransformResponse:
    transformed_text: str              # serialization_alias="transformedText"
    analysis_context: str | None       # serialization_alias="analysisContext"

class TierInfoResponse:
    tier: str                          # currently hardcoded "PAID"
    max_text_length: int               # serialization_alias="maxTextLength"
    prompt_enabled: bool               # serialization_alias="promptEnabled"
```

**Config:** All schemas use `populate_by_name=True` with camelCase aliases for frontend JSON compatibility.

## Tier System

Currently all users treated as **PAID** tier:
- Max text length: 2000 chars
- User prompt: enabled (max 500 chars)
- LLM max tokens: 4000
- No authentication required for transform endpoints

## Error Response Format

All errors return structured JSON:

```json
{
  "error": "ERROR_CODE",
  "message": "Korean user-facing message"
}
```

| HTTP | Error Code | Trigger |
|------|-----------|---------|
| 400 | INVALID_VERIFICATION_CODE | Wrong verification code |
| 400 | VERIFICATION_EXPIRED | Code past 5min expiry |
| 400 | EMAIL_NOT_VERIFIED | Signup without verification |
| 400 | INVALID_PASSWORD_FORMAT | Password validation failed |
| 400 | VALIDATION_ERROR | ValueError or Pydantic error |
| 401 | INVALID_CREDENTIALS | Wrong email/password |
| 403 | TIER_RESTRICTION | Feature not available |
| 404 | VERIFICATION_NOT_FOUND | No verification record |
| 409 | DUPLICATE_EMAIL | Email already registered |
| 409 | DUPLICATE_LOGIN_ID | Login ID taken |
| 422 | (FastAPI default) | Request schema validation |
| 500 | INTERNAL_ERROR | Unhandled exception |
| 503 | AI_TRANSFORM_ERROR | LLM API failure |
