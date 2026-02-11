import { useState, useCallback, useRef } from 'react';
import { Button, Input } from '@/shared/ui';
import { useTransformStore } from '@/shared/store';
import { MAX_PROMPT_LENGTH } from '@/shared/config/constants';
import { partialRewrite } from '@/features/partial-rewrite/api';
import { ApiError } from '@/shared/api/client';

export default function ResultPanel() {
  const { transformedText, persona, contexts, toneLevel, tierInfo, setTransformedText } = useTransformStore();
  const [copied, setCopied] = useState(false);
  const [selectedText, setSelectedText] = useState('');
  const [partialPrompt, setPartialPrompt] = useState('');
  const [isRewriting, setIsRewriting] = useState(false);
  const [rewriteError, setRewriteError] = useState<string | null>(null);
  const [tierMessage, setTierMessage] = useState<string | null>(null);
  const textRef = useRef<HTMLDivElement>(null);

  const isPartialRewriteEnabled = tierInfo?.partialRewriteEnabled ?? false;

  const handleCopy = async () => {
    await navigator.clipboard.writeText(transformedText);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleMouseUp = useCallback(() => {
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || !textRef.current) {
      return;
    }
    if (textRef.current.contains(selection.anchorNode)) {
      const text = selection.toString().trim();
      if (text) {
        setRewriteError(null);
        if (!isPartialRewriteEnabled) {
          setTierMessage('부분 재변환은 프리미엄 기능입니다.');
          setTimeout(() => setTierMessage(null), 3000);
          window.getSelection()?.removeAllRanges();
          return;
        }
        setSelectedText(text);
        setPartialPrompt('');
        setTierMessage(null);
      }
    }
  }, [isPartialRewriteEnabled]);

  const clearSelection = () => {
    setSelectedText('');
    setPartialPrompt('');
    setRewriteError(null);
    window.getSelection()?.removeAllRanges();
  };

  const handlePartialRewrite = async () => {
    if (!selectedText || !persona || !contexts.length || !toneLevel) return;

    setIsRewriting(true);
    setRewriteError(null);

    try {
      const response = await partialRewrite({
        selectedText,
        fullContext: transformedText,
        persona,
        contexts,
        toneLevel,
        userPrompt: partialPrompt || undefined,
      });

      const updated = transformedText.replace(selectedText, response.rewrittenText);
      setTransformedText(updated);
      clearSelection();
    } catch (err) {
      if (err instanceof ApiError) {
        setRewriteError(err.message);
      } else {
        setRewriteError('네트워크 오류가 발생했습니다.');
      }
    } finally {
      setIsRewriting(false);
    }
  };

  if (!transformedText) return null;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-text">변환 결과</h3>
        <Button variant="ghost" onClick={handleCopy} className="text-xs">
          {copied ? '복사됨!' : '복사'}
        </Button>
      </div>

      <div
        ref={textRef}
        onMouseUp={handleMouseUp}
        className="rounded-xl bg-surface/50 border border-border p-4 sm:p-5 cursor-text select-text"
      >
        <p className="text-sm text-text leading-relaxed whitespace-pre-wrap">
          {transformedText}
        </p>
      </div>

      {/* Tier restriction message */}
      {tierMessage && (
        <p className="text-xs text-accent font-medium animate-fade-in-up">
          {tierMessage}
        </p>
      )}

      {/* Hint for drag-to-rewrite */}
      {!selectedText && !tierMessage && (
        <p className="text-xs text-text-secondary">
          {isPartialRewriteEnabled
            ? '수정하고 싶은 부분을 드래그하면 부분 재변환할 수 있어요.'
            : '수정하고 싶은 부분을 드래그하면 부분 재변환할 수 있어요. (프리미엄 기능)'}
        </p>
      )}

      {/* Partial rewrite UI */}
      {selectedText && isPartialRewriteEnabled && (
        <div className="rounded-xl border border-accent/30 bg-accent-light/30 p-4 flex flex-col gap-3">
          <div className="flex items-start justify-between gap-2">
            <div className="flex-1 min-w-0">
              <p className="text-xs font-medium text-accent-deep mb-1">선택한 부분</p>
              <p className="text-sm text-text bg-white rounded-lg px-3 py-2 border border-border line-clamp-2">
                &ldquo;{selectedText}&rdquo;
              </p>
            </div>
            <button
              onClick={clearSelection}
              className="text-text-secondary hover:text-text text-xs shrink-0 mt-5 cursor-pointer"
            >
              취소
            </button>
          </div>
          <Input
            id="partial-prompt"
            placeholder="예: 좀 더 부드럽게"
            maxLength={MAX_PROMPT_LENGTH}
            value={partialPrompt}
            onChange={(e) => setPartialPrompt(e.target.value)}
          />
          {rewriteError && (
            <p className="text-xs text-error">{rewriteError}</p>
          )}
          <Button
            onClick={handlePartialRewrite}
            disabled={isRewriting}
            className="w-full py-2.5 text-sm font-semibold rounded-lg"
          >
            {isRewriting ? '재변환 중...' : '부분 재변환'}
          </Button>
        </div>
      )}
    </div>
  );
}
