import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import {useAuthStore, useTransformStore} from '@/shared/store';
import {PERSONAS, CONTEXTS} from '@/shared/config/constants';
import type {Persona, Context, ToneLevel} from '@/shared/config/constants';
import {ResultPanel} from '@/widgets/result-panel';
import {transformText, getTierInfo} from '@/features/transform/api';
import {ApiError} from '@/shared/api/client';

const TONE_SLIDER_LEVELS: {key: ToneLevel; label: string}[] = [
  {key: 'NEUTRAL', label: '중립'},
  {key: 'POLITE', label: '공손'},
  {key: 'VERY_POLITE', label: '매우 공손'},
];

const PERSONA_ICONS: Record<string, React.ReactNode> = {
  BOSS: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
      <circle cx="12" cy="7" r="4" />
    </svg>
  ),
  CLIENT: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
  ),
  PROFESSOR: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M22 10v6M2 10l10-5 10 5-10 5z" />
      <path d="M6 12v5c3 3 9 3 12 0v-5" />
    </svg>
  ),
  PARENT: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
      <polyline points="9 22 9 12 15 12 15 22" />
    </svg>
  ),
  OFFICIAL: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="2" width="16" height="20" rx="2" ry="2" />
      <path d="M9 22v-4h6v4" />
      <line x1="8" y1="6" x2="8.01" y2="6" />
      <line x1="16" y1="6" x2="16.01" y2="6" />
      <line x1="12" y1="6" x2="12.01" y2="6" />
      <line x1="8" y1="10" x2="8.01" y2="10" />
      <line x1="16" y1="10" x2="16.01" y2="10" />
      <line x1="12" y1="10" x2="12.01" y2="10" />
      <line x1="8" y1="14" x2="8.01" y2="14" />
      <line x1="16" y1="14" x2="16.01" y2="14" />
      <line x1="12" y1="14" x2="12.01" y2="14" />
    </svg>
  ),
  COLLEAGUE: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
  ),
};

export default function HomePage() {
  const {isLoggedIn, loginId, name} = useAuthStore();
  const {
    persona,
    contexts,
    toneLevel,
    originalText,
    transformedText,
    isTransforming,
    transformError,
    tierInfo,
    setPersona,
    toggleContext,
    setToneLevel,
    setOriginalText,
    setTransformedText,
    setIsTransforming,
    setTransformError,
    setTierInfo,
  } = useTransformStore();

  const [settingsOpen, setSettingsOpen] = useState(true);

  useEffect(() => {
    if (!toneLevel) setToneLevel('POLITE');
  }, [toneLevel, setToneLevel]);

  // Fetch tier info on mount and when login state changes
  useEffect(() => {
    getTierInfo()
      .then(setTierInfo)
      .catch(() => {
        // Fallback to free tier defaults
        setTierInfo({
          tier: 'FREE',
          maxTextLength: 300,
          partialRewriteEnabled: false,
          promptEnabled: false,
        });
      });
  }, [isLoggedIn, setTierInfo]);

  const maxTextLength = tierInfo?.maxTextLength ?? 300;

  const toneIndex = TONE_SLIDER_LEVELS.findIndex(
    (t) => t.key === (toneLevel ?? 'POLITE'),
  );

  const handleToneChange = (index: number) => {
    setToneLevel(TONE_SLIDER_LEVELS[index].key);
  };

  const handleTransform = async () => {
    if (!originalText.trim() || !persona || contexts.length === 0 || !toneLevel) return;

    setIsTransforming(true);
    setTransformError(null);

    try {
      const response = await transformText({
        persona,
        contexts,
        toneLevel,
        originalText,
      });
      setTransformedText(response.transformedText);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'TIER_RESTRICTION') {
          setTransformError(err.message);
        } else if (err.code === 'AI_TRANSFORM_ERROR') {
          setTransformError('AI 변환 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
        } else {
          setTransformError(err.message);
        }
      } else {
        setTransformError('네트워크 오류가 발생했습니다. 인터넷 연결을 확인해주세요.');
      }
    } finally {
      setIsTransforming(false);
    }
  };

  const isAllSelected = !!persona && contexts.length > 0 && !!toneLevel;
  const canTransform = isAllSelected && !!originalText.trim() && !isTransforming;
  const hasResult = !!transformedText;

  // Summary labels for mobile collapsed view
  const selectedPersonaLabel = persona ? PERSONAS.find(p => p.key === persona)?.label : null;
  const selectedContextLabels = contexts.map(c => CONTEXTS.find(ctx => ctx.key === c)?.label).filter(Boolean);
  const selectedToneLabel = TONE_SLIDER_LEVELS.find(t => t.key === toneLevel)?.label;

  // Shared settings content (rendered in both mobile collapsible & desktop sidebar)
  const renderSettings = () => (
    <>
      {/* Persona Section */}
      <section className="mb-8 lg:mb-10">
        <div className="flex items-center justify-between mb-4 lg:mb-5">
          <h3 className="text-sm font-semibold text-text-secondary">
            받는 사람 (PERSONA)
          </h3>
          <span className="text-xs text-error font-medium">*필수</span>
        </div>
        <div className="grid grid-cols-2 gap-2.5 lg:gap-3">
          {PERSONAS.map((p) => (
            <button
              key={p.key}
              onClick={() =>
                setPersona(persona === p.key ? null : (p.key as Persona))
              }
              className={`flex items-center gap-2.5 lg:gap-3 px-3.5 lg:px-4 py-3 lg:py-3.5 rounded-xl border text-sm font-medium transition-all cursor-pointer ${
                persona === p.key
                  ? 'bg-accent-light border-accent/30 text-accent-deep'
                  : 'border-border/60 bg-white text-text-secondary hover:border-accent/20 hover:bg-accent-light/30'
              }`}
            >
              <span
                className={
                  persona === p.key
                    ? 'text-accent-deep'
                    : 'text-text-secondary/60'
                }
              >
                {PERSONA_ICONS[p.key]}
              </span>
              {p.label}
            </button>
          ))}
        </div>
      </section>

      {/* Context Section */}
      <section className="mb-8 lg:mb-10">
        <h3 className="text-sm font-semibold text-text-secondary mb-4 lg:mb-5">
          상황 (CONTEXT)
        </h3>
        <div className="flex flex-wrap gap-2.5 lg:gap-3">
          {CONTEXTS.map((c) => (
            <button
              key={c.key}
              onClick={() => toggleContext(c.key as Context)}
              className={`px-3.5 lg:px-4 py-2 rounded-full text-sm font-medium border transition-all cursor-pointer ${
                contexts.includes(c.key as Context)
                  ? 'bg-accent text-white border-accent shadow-sm shadow-accent/20'
                  : 'border-border/60 text-text-secondary bg-white hover:border-accent/30 hover:text-accent'
              }`}
            >
              {c.label}
            </button>
          ))}
        </div>
      </section>

      {/* Tone Slider */}
      <section>
        <h3 className="text-sm font-semibold text-text-secondary mb-4 lg:mb-6">
          말투 강도
        </h3>
        <div className="px-2">
          <input
            type="range"
            min={0}
            max={TONE_SLIDER_LEVELS.length - 1}
            step={1}
            value={toneIndex >= 0 ? toneIndex : 1}
            onChange={(e) => handleToneChange(Number(e.target.value))}
            className="tone-slider w-full"
          />
          <div className="flex justify-between mt-2">
            {TONE_SLIDER_LEVELS.map((t, i) => (
              <span
                key={t.key}
                className={`text-xs transition-colors ${
                  i === (toneIndex >= 0 ? toneIndex : 1)
                    ? 'font-semibold text-text'
                    : 'text-text-secondary'
                }`}
              >
                {t.label}
              </span>
            ))}
          </div>
          <p className="text-xs text-accent mt-2">
            *기본값: 공손 (자연스러운 비즈니스 톤)
          </p>
        </div>
      </section>
    </>
  );

  return (
    <div className="h-screen flex flex-col lg:flex-row">
      {/* ===== MOBILE HEADER (hidden on desktop) ===== */}
      <header className="lg:hidden flex items-center justify-between px-4 py-3 border-b border-border bg-bg shrink-0">
        <div className="flex items-center gap-2">
          <img
            src="/politely_logo.png"
            alt="Politely"
            className="w-7 h-7 rounded-lg"
          />
          <span className="text-base font-bold text-text">Politely</span>
        </div>
        <div className="flex items-center gap-2">
          {isLoggedIn ? (
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-full bg-white border border-border/60 flex items-center justify-center">
                <svg
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  className="text-text-secondary"
                >
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                  <circle cx="12" cy="7" r="4" />
                </svg>
              </div>
              <span className="text-sm font-medium text-text">
                {name || loginId}
              </span>
            </div>
          ) : (
            <Link
              to="/login"
              className="px-3 py-1.5 text-xs font-semibold text-accent border border-accent/40 rounded-lg hover:bg-accent hover:text-white transition-colors"
            >
              로그인
            </Link>
          )}
        </div>
      </header>

      {/* ===== MOBILE COLLAPSIBLE SETTINGS (hidden on desktop) ===== */}
      <div className="lg:hidden shrink-0 border-b border-border bg-bg">
        {/* Settings content when open */}
        {settingsOpen && (
          <div className="px-4 pt-4 pb-1 overflow-y-auto max-h-[60vh]">
            {renderSettings()}
          </div>
        )}

        {/* Collapsed: mini selection chips */}
        {!settingsOpen && (persona || contexts.length > 0) && (
          <div className="px-4 pt-3 pb-1 flex items-center gap-1.5 flex-wrap">
            {persona && (
              <span className="px-2.5 py-1 bg-accent/10 text-accent text-xs font-medium rounded-full">
                {selectedPersonaLabel}
              </span>
            )}
            {selectedContextLabels.map((label) => (
              <span
                key={label}
                className="px-2.5 py-1 bg-accent/10 text-accent text-xs font-medium rounded-full"
              >
                {label}
              </span>
            ))}
            {toneLevel && (
              <span className="px-2.5 py-1 bg-accent/10 text-accent text-xs font-medium rounded-full">
                {selectedToneLabel}
              </span>
            )}
          </div>
        )}

        {/* Grab handle */}
        <button
          onClick={() => setSettingsOpen(!settingsOpen)}
          className="w-full flex justify-center py-2.5 cursor-pointer group"
        >
          <div className="w-10 h-1 rounded-full bg-border group-active:bg-text-secondary/50 transition-colors" />
        </button>
      </div>

      {/* ===== DESKTOP SIDEBAR (hidden on mobile) ===== */}
      <aside className="hidden lg:flex w-[440px] min-w-[400px] bg-bg border-r border-border flex-col shrink-0">
        {/* Logo */}
        <div className="px-8 py-6 flex items-center gap-2.5">
          <img
            src="/politely_logo.png"
            alt="Politely"
            className="w-8 h-8 rounded-lg"
          />
          <span className="text-lg font-bold text-text">Politely</span>
        </div>

        {/* Scrollable settings area */}
        <div className="flex-1 overflow-y-auto px-8 pb-8">
          {renderSettings()}
        </div>

        {/* User Info at bottom */}
        <div className="border-t border-border px-8 py-5">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-full bg-white border border-border/60 flex items-center justify-center shrink-0">
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                className="text-text-secondary"
              >
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
              </svg>
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-text truncate">
                {isLoggedIn ? name || loginId : 'Guest User'}
              </p>
              <p className="text-xs text-text-secondary">
                {isLoggedIn ? loginId : 'Free Tier'}
              </p>
            </div>
            {!isLoggedIn && (
              <Link
                to="/login"
                className="px-4 py-1.5 text-xs font-semibold text-accent border border-accent/40 rounded-lg hover:bg-accent hover:text-white transition-colors"
              >
                로그인
              </Link>
            )}
          </div>
        </div>
      </aside>

      {/* ===== MAIN PANEL ===== */}
      <main className="flex-1 flex flex-col min-h-0 bg-white">
        {/* Top bar with help/settings icons (desktop only) */}
        <div className="hidden lg:flex justify-end items-center px-10 py-5 gap-1">
          <button className="p-2 rounded-lg hover:bg-surface text-text-secondary/50 hover:text-text-secondary transition-colors cursor-pointer">
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <circle cx="12" cy="12" r="10" />
              <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
          </button>
          <button className="p-2 rounded-lg hover:bg-surface text-text-secondary/50 hover:text-text-secondary transition-colors cursor-pointer">
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
          </button>
        </div>

        {/* Text input area */}
        <div className="flex-1 overflow-y-auto px-4 sm:px-10 lg:px-14 pb-6 sm:pb-10 pt-5 lg:pt-0">
          <div className="flex items-baseline justify-between mb-5 lg:mb-8">
            <h1 className="text-lg sm:text-xl lg:text-2xl font-bold text-text">
              원문 입력
            </h1>
            <span
              className={`text-sm font-medium tabular-nums ${
                originalText.length > 0 ? 'text-error' : 'text-text-secondary'
              }`}
            >
              {originalText.length.toLocaleString()} /{' '}
              {maxTextLength.toLocaleString()}
            </span>
          </div>

          <textarea
            placeholder="다듬고 싶은 텍스트를 입력하세요..."
            maxLength={maxTextLength}
            value={originalText}
            onChange={(e) => setOriginalText(e.target.value)}
            className="w-full min-h-[220px] sm:min-h-[280px] lg:min-h-[360px] text-base text-text leading-relaxed placeholder:text-text-secondary/40 resize-none outline-none bg-transparent"
          />

          {/* Error message */}
          {transformError && (
            <div className="mt-4 p-4 rounded-xl bg-error/5 border border-error/20 text-sm text-error animate-fade-in-up">
              {transformError}
            </div>
          )}

          {/* Result area */}
          {hasResult && (
            <div className="mt-8 pt-8 border-t border-border animate-fade-in-up">
              <ResultPanel />
            </div>
          )}
        </div>

        {/* Bottom bar with status + transform button */}
        <div className="border-t border-border px-4 sm:px-10 lg:px-14 py-4 sm:py-6 flex items-center justify-between gap-3">
          <div className="flex items-center gap-2 text-sm text-text-secondary min-w-0">
            <span
              className={`w-2 h-2 rounded-full shrink-0 ${
                canTransform ? 'bg-success' : 'bg-border'
              }`}
            />
            <span className="hidden sm:inline truncate">
              {isTransforming
                ? '변환 중...'
                : canTransform
                  ? '준비 완료. 변환 버튼을 눌러주세요.'
                  : !isAllSelected
                    ? '받는 사람, 상황, 말투 강도를 선택하세요.'
                    : '텍스트를 입력하면 변환할 수 있어요.'}
            </span>
            <span className="sm:hidden truncate">
              {isTransforming
                ? '변환 중...'
                : canTransform
                  ? '준비 완료'
                  : !isAllSelected
                    ? '옵션을 선택하세요'
                    : '텍스트를 입력하세요'}
            </span>
            {tierInfo && (
              <span className="text-xs text-text-secondary/60 ml-2 hidden sm:inline shrink-0">
                {tierInfo.tier === 'FREE'
                  ? `프리티어 · 최대 ${tierInfo.maxTextLength}자`
                  : `프리미엄 · 최대 ${tierInfo.maxTextLength.toLocaleString()}자`}
              </span>
            )}
          </div>
          <button
            onClick={handleTransform}
            disabled={!canTransform}
            className="px-5 sm:px-6 py-3 bg-text text-white text-sm font-semibold rounded-xl hover:bg-primary-light transition-colors disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer shrink-0 flex items-center gap-2"
          >
            {isTransforming ? (
              <>
                <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                변환 중...
              </>
            ) : (
              <>
                말투 다듬기
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <line x1="5" y1="12" x2="19" y2="12" />
                  <polyline points="12 5 19 12 12 19" />
                </svg>
              </>
            )}
          </button>
        </div>
      </main>
    </div>
  );
}
