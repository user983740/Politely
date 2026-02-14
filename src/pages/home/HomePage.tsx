import {useEffect, useRef, useState} from 'react';
import {Link} from 'react-router-dom';
import {useAuthStore, useTransformStore} from '@/shared/store';
import {useNavigate} from 'react-router-dom';
import {PERSONAS, CONTEXTS, MAX_PROMPT_LENGTH, MAX_SENDER_INFO_LENGTH} from '@/shared/config/constants';
import type {Persona, Context, ToneLevel} from '@/shared/config/constants';
import {ResultPanel} from '@/widgets/result-panel';
import {AnalysisPanel} from '@/widgets/analysis-panel';
import {CostPanel} from '@/widgets/cost-panel';
import {QualityReportPanel} from '@/widgets/quality-report-panel';
import {PipelineTracePanel} from '@/widgets/pipeline-trace-panel';
import {streamTransform} from '@/features/transform/stream-api';
import type {LockedSpanInfo} from '@/features/transform/stream-api';
import {ApiError} from '@/shared/api/client';

const TONE_SLIDER_LEVELS: {key: ToneLevel; label: string}[] = [
  {key: 'NEUTRAL', label: '중립'},
  {key: 'POLITE', label: '공손'},
  {key: 'VERY_POLITE', label: '매우 공손'},
];

const PERSONA_ICONS: Record<string, React.ReactNode> = {
  BOSS: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
      <circle cx="12" cy="7" r="4" />
    </svg>
  ),
  CLIENT: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
  ),
  PROFESSOR: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M22 10v6M2 10l10-5 10 5-10 5z" />
      <path d="M6 12v5c3 3 9 3 12 0v-5" />
    </svg>
  ),
  PARENT: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
      <polyline points="9 22 9 12 15 12 15 22" />
    </svg>
  ),
  OFFICIAL: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
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
  OTHER: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="5" cy="12" r="2" />
      <circle cx="12" cy="12" r="2" />
      <circle cx="19" cy="12" r="2" />
    </svg>
  ),
};

export default function HomePage() {
  const {isLoggedIn, loginId, name, setLoggedOut} = useAuthStore();
  const navigate = useNavigate();
  const {
    persona,
    contexts,
    toneLevel,
    originalText,
    userPrompt,
    senderInfo,
    transformedText,
    labels,
    pipelineStats,
    segments,
    maskedText,
    relationIntent,
    processedText,
    validationIssues,
    isTransforming,
    transformError,
    usageInfo,
    setPersona,
    toggleContext,
    setToneLevel,
    setOriginalText,
    setUserPrompt,
    setSenderInfo,
    setTransformedText,
    setAnalysisContext,
    setLabels,
    setPipelineStats,
    setSegments,
    setMaskedText,
    setRelationIntent,
    setProcessedText,
    setValidationIssues,
    currentPhase,
    setCurrentPhase,
    setIsTransforming,
    setTransformError,
    setUsageInfo,
    resetForNewInput,
  } = useTransformStore();

  const [settingsOpen, setSettingsOpen] = useState(false);
  const [identityBoosterToggle, setIdentityBoosterToggle] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const rawStreamRef = useRef('');
  const spansRef = useRef<LockedSpanInfo[]>([]);

  const maxTextLength = 2000;

  useEffect(() => {
    if (!toneLevel) setToneLevel('POLITE');
  }, [toneLevel, setToneLevel]);

  const toneIndex = TONE_SLIDER_LEVELS.findIndex(
    (t) => t.key === (toneLevel ?? 'POLITE'),
  );

  const handleToneChange = (index: number) => {
    setToneLevel(TONE_SLIDER_LEVELS[index].key);
  };

  const handleTransform = async () => {
    if (!originalText.trim() || !persona || contexts.length === 0 || !toneLevel) return;

    // Cancel any in-progress stream
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setIsTransforming(true);
    setTransformError(null);
    setTransformedText('');
    setAnalysisContext(null);
    setLabels(null);
    setPipelineStats(null);
    setSegments(null);
    setMaskedText(null);
    setRelationIntent(null);
    setProcessedText(null);
    setValidationIssues(null);
    setCurrentPhase(null);
    setUsageInfo(null);
    rawStreamRef.current = '';
    spansRef.current = [];

    try {
      await streamTransform(
        {
          persona,
          contexts,
          toneLevel,
          originalText,
          ...(userPrompt.trim() ? {userPrompt: userPrompt.trim()} : {}),
          ...(senderInfo.trim() ? {senderInfo: senderInfo.trim()} : {}),
          identityBoosterToggle,
        },
        {
          onSpans: (spans) => {
            spansRef.current = spans;
          },
          onLabels: (data) => setLabels(data),
          onStats: (data) => setPipelineStats(data),
          onDelta: (chunk) => {
            rawStreamRef.current += chunk;
            if (spansRef.current.length > 0) {
              let text = rawStreamRef.current;
              for (const span of spansRef.current) {
                text = text.replaceAll(span.placeholder, span.original);
              }
              setTransformedText(text);
            } else {
              setTransformedText(rawStreamRef.current);
            }
          },
          onSegments: (data) => setSegments(data),
          onMaskedText: (text) => setMaskedText(text),
          onRelationIntent: (data) => setRelationIntent(data),
          onProcessedText: (text) => setProcessedText(text),
          onValidationIssues: (issues) => setValidationIssues(issues),
          onPhase: (phase) => setCurrentPhase(phase),
          onUsage: (usage) => setUsageInfo(usage),
          onDone: (fullText) => setTransformedText(fullText),
          onError: (message) => setTransformError(message),
          onRetry: () => {
            rawStreamRef.current = '';
            setTransformedText('');
          },
        },
        controller.signal,
      );
    } catch (err) {
      if (controller.signal.aborted) return; // User cancelled

      const apiErr = err instanceof ApiError ? err
        : (err && typeof err === 'object' && 'code' in err && 'status' in err)
          ? err as ApiError
          : null;

      if (apiErr) {
        setTransformError(apiErr.message);
      } else {
        console.error('Transform error:', err);
        setTransformError('네트워크 오류가 발생했습니다. 인터넷 연결을 확인해주세요.');
      }
    } finally {
      setIsTransforming(false);
      abortRef.current = null;
    }
  };

  const SENDER_INFO_PLACEHOLDERS: Record<string, string> = {
    BOSS: '예: 마케팅팀 김민수 대리',
    CLIENT: '예: (주)ABC 영업부 이지연 과장',
    PROFESSOR: '예: 경영학과 20221234 홍길동',
    PARENT: '예: 3학년 2반 담임 김민수',
    OFFICIAL: '예: (주)ABC 김민수',
    OTHER: '예: OO팀 김민수',
  };

  const senderInfoPlaceholder = persona
    ? SENDER_INFO_PLACEHOLDERS[persona] ?? '예: OO팀 OOO'
    : '예: OO팀 OOO';

  const isAllSelected = !!persona && contexts.length > 0 && !!toneLevel;
  const canTransform = isAllSelected && !!originalText.trim() && !isTransforming;
  const hasResult = !!transformedText || isTransforming;

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

      {/* Identity Booster Toggle */}
      <section className="mt-8 lg:mt-10">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold text-text-secondary">고유명사 보호 강화</h3>
            <p className="text-xs text-text-secondary/60 mt-0.5">인명, 회사명 등 고유 표현 보호</p>
          </div>
          <button
            onClick={() => setIdentityBoosterToggle(!identityBoosterToggle)}
            className="flex items-center cursor-pointer"
          >
            <div className={`relative w-8 h-[18px] rounded-full transition-colors ${identityBoosterToggle ? 'bg-accent' : 'bg-border'}`}>
              <div className={`absolute top-[3px] w-3 h-3 rounded-full bg-white shadow transition-transform ${identityBoosterToggle ? 'translate-x-[14px]' : 'translate-x-[3px]'}`} />
            </div>
          </button>
        </div>
      </section>
    </>
  );

  // ===== RESULT MODE =====
  if (hasResult) {
    return (
      <div className="h-screen flex flex-col bg-white">
        <title>변환 결과 - Politely</title>
        {/* Result mode top bar */}
        <header className="flex items-center justify-between px-4 sm:px-8 lg:px-10 py-4 sm:py-5 border-b border-border shrink-0">
          <div className="flex items-center gap-2.5">
            <img
              src="/politely_logo.png"
              alt="Politely"
              className="w-7 h-7 sm:w-8 sm:h-8 rounded-lg"
            />
            <span className="text-base sm:text-lg font-bold text-text">Politely</span>
          </div>
          <div className="flex items-center gap-2">
            {isLoggedIn && (
              <button
                onClick={() => { setLoggedOut(); navigate('/'); }}
                className="px-3 py-2 text-sm text-text-secondary border border-border/60 rounded-xl hover:text-text hover:border-border transition-colors cursor-pointer"
              >
                로그아웃
              </button>
            )}
            <button
              onClick={resetForNewInput}
              className="flex items-center gap-2 px-4 py-2.5 text-sm font-semibold text-text border border-border/60 rounded-xl hover:bg-surface transition-colors cursor-pointer"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <polyline points="1 4 1 10 7 10" />
                <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
              </svg>
              새로 입력하기
            </button>
          </div>
        </header>

        {/* Result content centered */}
        <section className="flex-1 overflow-y-auto px-4 sm:px-8 lg:px-10 py-6 sm:py-10">
          <article className="mx-auto max-w-2xl space-y-4">
            <PipelineTracePanel
              currentPhase={currentPhase}
              isTransforming={isTransforming}
              spans={spansRef.current.length > 0 ? spansRef.current : null}
              segments={segments}
              maskedText={maskedText}
              labels={labels}
              relationIntent={relationIntent}
              processedText={processedText}
              validationIssues={validationIssues}
              transformedText={transformedText}
              transformError={transformError}
            />
            <ResultPanel />
            <AnalysisPanel labels={labels} />
            {pipelineStats && <QualityReportPanel stats={pipelineStats} />}
            {usageInfo && <CostPanel usageInfo={usageInfo} />}

            {/* Selection summary chips */}
            <footer className="pt-5 border-t border-border/60">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-xs text-text-secondary mr-1">설정</span>
                {selectedPersonaLabel && (
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
                {selectedToneLabel && (
                  <span className="px-2.5 py-1 bg-accent/10 text-accent text-xs font-medium rounded-full">
                    {selectedToneLabel}
                  </span>
                )}
              </div>
            </footer>
          </article>
        </section>
      </div>
    );
  }

  // ===== INPUT MODE =====
  return (
    <div className="h-screen flex flex-col lg:flex-row">
      <title>Politely - 한국어 말투 다듬기</title>
      <meta name="description" content="보내기 전 마지막으로 말투를 점검하는 안전망. 상사, 고객, 교수 등 상대방에 맞춰 자연스러운 경어체로 변환해 드립니다." />
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
              <span className="text-sm font-medium text-text truncate max-w-24">
                {name || loginId}
              </span>
              <button
                onClick={() => { setLoggedOut(); navigate('/'); }}
                className="px-2.5 py-1 text-xs text-text-secondary border border-border/60 rounded-lg hover:text-text hover:border-border transition-colors cursor-pointer"
              >
                로그아웃
              </button>
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
          <div className="px-4 pt-4 pb-1">
            {renderSettings()}
          </div>
        )}

        {/* Collapsed: mini selection chips or guide text */}
        {!settingsOpen && (
          <div className="px-4 pt-3 pb-1">
            {persona || contexts.length > 0 ? (
              <div className="flex items-center gap-1.5 flex-wrap">
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
            ) : (
              <p className="text-xs text-text-secondary text-center">
                아래를 눌러 받는 사람·상황·말투를 설정하세요
              </p>
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
                aria-hidden="true"
              >
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
              </svg>
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-text truncate">
                {isLoggedIn ? name || loginId : 'Guest User'}
              </p>
              <p className="text-xs text-text-secondary">Premium</p>
            </div>
            {isLoggedIn ? (
              <button
                onClick={() => { setLoggedOut(); navigate('/'); }}
                className="px-3 py-1.5 text-xs text-text-secondary border border-border/60 rounded-lg hover:text-text hover:border-border transition-colors cursor-pointer shrink-0"
              >
                로그아웃
              </button>
            ) : (
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
          <button className="p-2 rounded-lg hover:bg-surface text-text-secondary/50 hover:text-text-secondary transition-colors cursor-pointer" aria-label="도움말">
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="10" />
              <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
          </button>
          <button className="p-2 rounded-lg hover:bg-surface text-text-secondary/50 hover:text-text-secondary transition-colors cursor-pointer" aria-label="설정">
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
          </button>
        </div>

        {/* Text input area */}
        <div className="flex-1 flex flex-col overflow-y-auto px-4 sm:px-10 lg:px-14 pb-2 sm:pb-10 pt-5 lg:pt-0">
          {/* Sender Info */}
          <div className="mb-3 sm:mb-4 shrink-0">
            <div className="flex items-center justify-between mb-1 sm:mb-1.5">
              <label className="text-xs sm:text-sm font-medium text-text-secondary">보내는 사람</label>
              <span className={`text-xs tabular-nums ${senderInfo.length > 0 ? 'text-accent' : 'text-text-secondary/50'}`}>
                {senderInfo.length} / {MAX_SENDER_INFO_LENGTH}
              </span>
            </div>
            <input
              type="text"
              placeholder={senderInfoPlaceholder}
              maxLength={MAX_SENDER_INFO_LENGTH}
              value={senderInfo}
              onChange={(e) => setSenderInfo(e.target.value)}
              className="w-full px-3 sm:px-4 py-2 sm:py-2.5 text-sm text-text bg-surface border border-border/60 rounded-xl placeholder:text-text-secondary/40 outline-none focus:border-accent/40 transition-colors"
            />
          </div>

          <div className="flex items-baseline justify-between mb-3 sm:mb-5 lg:mb-4">
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
            className="w-full flex-1 min-h-[80px] sm:min-h-[280px] lg:min-h-[360px] text-base text-text leading-relaxed placeholder:text-text-secondary/40 resize-none outline-none bg-transparent"
          />

          {/* Additional prompt input */}
          <div className="mt-1 sm:mt-4 shrink-0">
            <div className="flex items-center justify-between mb-1 sm:mb-2">
              <label className="text-xs sm:text-sm font-medium text-text-secondary">추가 정보</label>
              <span className={`text-xs tabular-nums ${userPrompt.length > 0 ? 'text-accent' : 'text-text-secondary/50'}`}>
                {userPrompt.length} / {MAX_PROMPT_LENGTH}
              </span>
            </div>
            <input
              type="text"
              placeholder="예: 첫 거래처라 조심스러운 상황, 두 번째 요청이라 미안한 맥락"
              maxLength={MAX_PROMPT_LENGTH}
              value={userPrompt}
              onChange={(e) => setUserPrompt(e.target.value)}
              className="w-full px-3 sm:px-4 py-2 sm:py-3 text-sm text-text bg-surface border border-border/60 rounded-xl placeholder:text-text-secondary/40 outline-none focus:border-accent/40 transition-colors"
            />
          </div>

          {/* Error message */}
          {transformError && (
            <div className="mt-3 sm:mt-4 p-4 rounded-xl bg-error/5 border border-error/20 text-sm text-error shrink-0">
              {transformError}
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
            <span className="text-xs text-text-secondary/60 ml-2 hidden sm:inline shrink-0">
              최대 {maxTextLength.toLocaleString()}자
            </span>
          </div>
          <button
            onClick={handleTransform}
            disabled={!canTransform}
            className="px-5 sm:px-6 py-3 text-white text-sm font-semibold rounded-xl transition-colors disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer shrink-0 flex items-center gap-2 bg-text hover:bg-primary-light"
          >
            {isTransforming ? (
              <>
                <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" aria-hidden="true">
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
                  aria-hidden="true"
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
