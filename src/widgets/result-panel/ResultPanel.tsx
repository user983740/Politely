import { useState } from 'react';
import { Button } from '@/shared/ui';
import { useTransformStore } from '@/shared/store';

export default function ResultPanel() {
  const { transformedText, isTransforming } = useTransformStore();
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(transformedText);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  if (!transformedText && !isTransforming) return null;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-text">변환 결과</h3>
        <Button variant="ghost" onClick={handleCopy} className="text-xs" disabled={isTransforming || !transformedText}>
          {copied ? '복사됨!' : '복사'}
        </Button>
      </div>

      <div className="rounded-xl bg-surface/50 border border-border p-4 sm:p-5">
        <p className="text-sm text-text leading-relaxed whitespace-pre-wrap">
          {transformedText}
          {isTransforming && (
            <span className="inline-block w-0.5 h-4 bg-accent animate-pulse ml-0.5 align-text-bottom" />
          )}
        </p>
        {isTransforming && !transformedText && (
          <span className="text-sm text-text-secondary animate-pulse">변환 중...</span>
        )}
      </div>
    </div>
  );
}
