import {useRef} from 'react';
import {Link} from 'react-router-dom';
import {useAuthStore, useTransformStore} from '@/shared/store';
import {useNavigate} from 'react-router-dom';
import {MAX_PROMPT_LENGTH, MAX_SENDER_INFO_LENGTH} from '@/shared/config/constants';
import {ResultPanel} from '@/widgets/result-panel';
import {ABResultPanel} from '@/widgets/ab-result-panel';
import {AnalysisPanel} from '@/widgets/analysis-panel';
import {CostPanel} from '@/widgets/cost-panel';
import {QualityReportPanel} from '@/widgets/quality-report-panel';
import {PipelineTracePanel} from '@/widgets/pipeline-trace-panel';
import {streamTransform, streamTransformAB} from '@/features/transform/stream-api';
import type {LockedSpanInfo, SegmentData, LabelData, SituationAnalysisData, ProcessedSegmentsData, ValidationIssueData, PipelinePhase, TemplateSelectedData, StatsData, UsageInfo, CushionStrategyData} from '@/features/transform/stream-api';
import {ApiError} from '@/shared/api/client';

function DetailSection({
  currentPhase,
  isTransforming,
  spans,
  segments,
  maskedText,
  labels,
  situationAnalysis,
  processedSegments,
  validationIssues,
  chosenTemplate,
  transformedText,
  transformError,
  pipelineStats,
  usageInfo,
}: {
  currentPhase: PipelinePhase | null;
  isTransforming: boolean;
  spans: LockedSpanInfo[] | null;
  segments: SegmentData[] | null;
  maskedText: string | null;
  labels: LabelData[] | null;
  situationAnalysis: SituationAnalysisData | null;
  processedSegments: ProcessedSegmentsData | null;
  validationIssues: ValidationIssueData[] | null;
  chosenTemplate: TemplateSelectedData | null;
  transformedText: string;
  transformError: string | null;
  pipelineStats: StatsData | null;
  usageInfo: UsageInfo | null;
}) {
  return (
    <div className="space-y-4">
      <PipelineTracePanel
        currentPhase={currentPhase}
        isTransforming={isTransforming}
        spans={spans}
        segments={segments}
        maskedText={maskedText}
        labels={labels}
        situationAnalysis={situationAnalysis}
        processedSegments={processedSegments}
        validationIssues={validationIssues}
        chosenTemplate={chosenTemplate}
        transformedText={transformedText}
        transformError={transformError}
      />
      {pipelineStats && <QualityReportPanel stats={pipelineStats} />}
      {usageInfo && <CostPanel usageInfo={usageInfo} />}
    </div>
  );
}

export default function HomePage() {
  const {isLoggedIn, loginId, name, setLoggedOut} = useAuthStore();
  const navigate = useNavigate();
  const {
    originalText,
    userPrompt,
    senderInfo,
    transformedText,
    labels,
    pipelineStats,
    segments,
    maskedText,
    situationAnalysis,
    processedSegments,
    validationIssues,
    chosenTemplate,
    isTransforming,
    transformError,
    usageInfo,
    setOriginalText,
    setUserPrompt,
    setSenderInfo,
    setTransformedText,
    setLabels,
    setPipelineStats,
    setSegments,
    setMaskedText,
    setSituationAnalysis,
    setProcessedSegments,
    setValidationIssues,
    setChosenTemplate,
    currentPhase,
    setCurrentPhase,
    setIsTransforming,
    setTransformError,
    setUsageInfo,
    abMode,
    setAbMode,
    transformedTextB,
    setTransformedTextB,
    validationIssuesA,
    setValidationIssuesA,
    validationIssuesB,
    setValidationIssuesB,
    cushionStrategy,
    setCushionStrategy,
    resetForNewInput,
  } = useTransformStore();

  const abortRef = useRef<AbortController | null>(null);
  const rawStreamRefA = useRef('');
  const rawStreamRefB = useRef('');
  const spansRef = useRef<LockedSpanInfo[]>([]);

  const maxTextLength = 2000;

  const unmasked = (raw: string) => {
    if (spansRef.current.length === 0) return raw;
    let text = raw;
    for (const span of spansRef.current) {
      text = text.replaceAll(span.placeholder, span.original);
    }
    return text;
  };

  const handleTransform = async () => {
    if (!originalText.trim() || isTransforming) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setIsTransforming(true);
    setTransformError(null);
    setTransformedText('');
    setTransformedTextB('');
    setLabels(null);
    setPipelineStats(null);
    setSegments(null);
    setMaskedText(null);
    setSituationAnalysis(null);
    setProcessedSegments(null);
    setValidationIssues(null);
    setValidationIssuesA(null);
    setValidationIssuesB(null);
    setChosenTemplate(null);
    setCurrentPhase(null);
    setUsageInfo(null);
    setCushionStrategy(null);
    rawStreamRefA.current = '';
    rawStreamRefB.current = '';
    spansRef.current = [];

    const reqBody = {
      originalText,
      ...(senderInfo.trim() ? {senderInfo: senderInfo.trim()} : {}),
      ...(userPrompt.trim() ? {userPrompt: userPrompt.trim()} : {}),
    };

    const sharedCallbacks = {
      onSpans: (spans: LockedSpanInfo[]) => { spansRef.current = spans; },
      onLabels: (data: LabelData[]) => setLabels(data),
      onStats: (data: StatsData) => setPipelineStats(data),
      onTemplateSelected: (data: TemplateSelectedData) => setChosenTemplate(data),
      onSegments: (data: SegmentData[]) => setSegments(data),
      onMaskedText: (text: string) => setMaskedText(text),
      onSituationAnalysis: (data: SituationAnalysisData) => setSituationAnalysis(data),
      onProcessedSegments: (data: ProcessedSegmentsData) => setProcessedSegments(data),
      onPhase: (phase: PipelinePhase) => setCurrentPhase(phase),
      onUsage: (usage: UsageInfo) => setUsageInfo(usage),
      onError: (message: string) => setTransformError(message),
      onCushionStrategy: (data: CushionStrategyData) => setCushionStrategy(data),
    };

    try {
      if (abMode) {
        await streamTransformAB(reqBody, {
          ...sharedCallbacks,
          onDelta: (chunk) => {
            rawStreamRefA.current += chunk;
            setTransformedText(unmasked(rawStreamRefA.current));
          },
          onDeltaB: (chunk) => {
            rawStreamRefB.current += chunk;
            setTransformedTextB(unmasked(rawStreamRefB.current));
          },
          onDoneA: (text) => setTransformedText(text),
          onDoneB: (text) => setTransformedTextB(text),
          onValidationA: (issues) => setValidationIssuesA(issues),
          onValidationB: (issues) => setValidationIssuesB(issues),
          onValidationIssues: (issues) => setValidationIssues(issues),
          onDone: () => {},
        }, controller.signal);
      } else {
        await streamTransform(reqBody, {
          ...sharedCallbacks,
          onDelta: (chunk) => {
            rawStreamRefA.current += chunk;
            setTransformedText(unmasked(rawStreamRefA.current));
          },
          onValidationIssues: (issues) => setValidationIssues(issues),
          onDone: (fullText) => setTransformedText(fullText),
          onRetry: () => {
            rawStreamRefA.current = '';
            setTransformedText('');
          },
        }, controller.signal);
      }
    } catch (err) {
      if (controller.signal.aborted) return;

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

  const canTransform = !!originalText.trim() && !isTransforming;
  const hasResult = !!transformedText || !!transformedTextB || isTransforming;

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
          <article className={`mx-auto space-y-4 ${abMode ? 'max-w-5xl' : 'max-w-2xl'}`}>
            {abMode ? (
              <ABResultPanel
                textA={transformedText}
                textB={transformedTextB}
                isTransforming={isTransforming}
                currentPhase={currentPhase}
                validationIssuesA={validationIssuesA}
                validationIssuesB={validationIssuesB}
                cushionStrategy={cushionStrategy}
              />
            ) : (
              <ResultPanel />
            )}
            <AnalysisPanel labels={labels} />

            {/* Developer details */}
            {(pipelineStats || usageInfo || currentPhase) && (
              <DetailSection
                currentPhase={currentPhase}
                isTransforming={isTransforming}
                spans={spansRef.current.length > 0 ? spansRef.current : null}
                segments={segments}
                maskedText={maskedText}
                labels={labels}
                situationAnalysis={situationAnalysis}
                processedSegments={processedSegments}
                validationIssues={validationIssues}
                chosenTemplate={chosenTemplate}
                transformedText={transformedText}
                transformError={transformError}
                pipelineStats={pipelineStats}
                usageInfo={usageInfo}
              />
            )}
          </article>
        </section>
      </div>
    );
  }

  // ===== INPUT MODE =====
  return (
    <div className="h-screen flex flex-col bg-white">
      <title>Politely - 한국어 말투 다듬기</title>
      <meta name="description" content="메시지 보내기 전, AI가 말투를 점검해 드립니다. 상대방에 맞춰 자연스러운 존댓말로 바꿔주는 한국어 톤 변환 도구." />

      {/* Header */}
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
          {isLoggedIn ? (
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium text-text truncate max-w-24 hidden sm:inline">
                {name || loginId}
              </span>
              <button
                onClick={() => { setLoggedOut(); navigate('/'); }}
                className="px-3 py-1.5 text-xs text-text-secondary border border-border/60 rounded-lg hover:text-text hover:border-border transition-colors cursor-pointer"
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

      {/* Main content — single column centered */}
      <main className="flex-1 flex flex-col min-h-0">
        <div className="flex-1 flex flex-col overflow-y-auto px-4 sm:px-8 lg:px-10 pt-6 sm:pt-10 pb-2 sm:pb-10">
          <div className="mx-auto w-full max-w-2xl flex flex-col flex-1">
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
                placeholder="예: OO팀 김민수 대리"
                maxLength={MAX_SENDER_INFO_LENGTH}
                value={senderInfo}
                onChange={(e) => setSenderInfo(e.target.value)}
                className="w-full px-3 sm:px-4 py-2 sm:py-2.5 text-sm text-text bg-surface border border-border/60 rounded-xl placeholder:text-text-secondary/40 outline-none focus:border-accent/40 transition-colors"
              />
            </div>

            {/* Title + char count */}
            <div className="mb-3 sm:mb-5 lg:mb-4">
              <div className="flex items-baseline justify-between">
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
              <p className="text-[13px] text-text-secondary/60 mt-1.5">
                AI가 메시지 말투를 자연스러운 비즈니스 존댓말로 다듬어 드립니다.
              </p>
            </div>

            {/* Textarea */}
            <textarea
              placeholder="다듬고 싶은 텍스트를 입력하세요..."
              maxLength={maxTextLength}
              value={originalText}
              onChange={(e) => setOriginalText(e.target.value)}
              className="w-full flex-1 min-h-[120px] sm:min-h-[280px] lg:min-h-[360px] text-base text-text leading-relaxed placeholder:text-text-secondary/40 resize-none outline-none bg-transparent"
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
        </div>

        {/* Bottom bar with status + AB toggle + transform button */}
        <div className="border-t border-border px-4 sm:px-8 lg:px-10 py-4 sm:py-6 flex items-center justify-between gap-3">
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
                  : '텍스트를 입력하면 변환할 수 있어요.'}
            </span>
            <span className="sm:hidden truncate">
              {isTransforming
                ? '변환 중...'
                : canTransform
                  ? '준비 완료'
                  : '텍스트를 입력하세요'}
            </span>
            <span className="text-xs text-text-secondary/60 ml-2 hidden sm:inline shrink-0">
              최대 {maxTextLength.toLocaleString()}자
            </span>
          </div>
          <div className="flex items-center gap-3 shrink-0">
            <button
              onClick={() => setAbMode(!abMode)}
              disabled={isTransforming}
              className={`hidden sm:flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors cursor-pointer disabled:opacity-30 disabled:cursor-not-allowed ${
                abMode
                  ? 'bg-violet-50 border-violet-300 text-violet-700'
                  : 'bg-white border-border/60 text-text-secondary hover:border-border hover:text-text'
              }`}
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <rect x="3" y="3" width="7" height="18" rx="1" />
                <rect x="14" y="3" width="7" height="18" rx="1" />
              </svg>
              A/B
            </button>
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
        </div>
      </main>
    </div>
  );
}
