import { ChipGroup, Textarea, Button } from '@/shared/ui';
import { useTransformStore } from '@/shared/store';
import {
  PERSONAS,
  CONTEXTS,
  TONE_LEVELS,
  MAX_TEXT_LENGTH,
} from '@/shared/config/constants';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';

const DUMMY_RESULT = `안녕하세요, 담당자님.

해당 건 진행 상황이 어떻게 되고 있는지 여쭤봐도 될까요?
가능하시다면 조금 더 빠른 처리를 부탁드려도 괜찮을지요.

바쁘신 와중에 번거롭게 해드려 죄송합니다.
감사합니다.`;

function StepLabel({ step, label }: { step: number; label: string }) {
  return (
    <div className="flex items-center gap-2 mb-3">
      <span className="w-5 h-5 rounded-full bg-accent text-white text-xs font-bold flex items-center justify-center shrink-0">
        {step}
      </span>
      <span className="text-sm font-semibold text-text">{label}</span>
    </div>
  );
}

export default function TransformPanel() {
  const {
    persona,
    contexts,
    toneLevel,
    originalText,
    setPersona,
    toggleContext,
    setToneLevel,
    setOriginalText,
    setTransformedText,
  } = useTransformStore();

  const handleTransform = () => {
    if (!originalText.trim()) return;
    // TODO: replace with real API call
    setTransformedText(DUMMY_RESULT);
  };

  return (
    <div className="flex flex-col gap-6">
      <div>
        <StepLabel step={1} label="받는 사람" />
        <ChipGroup
          hint="누구에게 보내는 메시지인가요?"
          options={PERSONAS}
          selected={persona ?? ''}
          onSelect={(key) => setPersona(persona === key ? null : (key as Persona))}
        />
      </div>

      <div>
        <StepLabel step={2} label="어떤 상황인가요?" />
        <div className="flex flex-col gap-4">
          <ChipGroup
            hint="복수 선택 가능"
            options={CONTEXTS}
            selected={contexts}
            onSelect={(key) => toggleContext(key as Context)}
          />
          <ChipGroup
            label="톤 레벨"
            hint="어떤 느낌으로 다듬을까요?"
            options={TONE_LEVELS}
            selected={toneLevel ?? ''}
            onSelect={(key) => setToneLevel(toneLevel === key ? null : (key as ToneLevel))}
          />
        </div>
      </div>

      <div>
        <StepLabel step={3} label="원문 입력" />
        <Textarea
          id="original-text"
          placeholder={'예: 이거 왜 아직도 안 됐어요? 빨리 좀 해주세요.\n\n다듬고 싶은 텍스트를 입력하세요.'}
          maxLength={MAX_TEXT_LENGTH}
          value={originalText}
          onChange={(e) => setOriginalText(e.target.value)}
          rows={4}
        />
      </div>

      <Button
        onClick={handleTransform}
        disabled={!originalText.trim()}
        className="w-full py-3 text-base font-semibold rounded-xl bg-gradient-to-r from-accent-deep to-accent hover:opacity-90 disabled:from-border disabled:to-border"
      >
        변환하기
      </Button>
    </div>
  );
}
