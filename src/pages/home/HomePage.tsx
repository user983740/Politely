import { useState, useEffect, useLayoutEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { Layout } from '@/shared/ui';
import { TransformPanel } from '@/widgets/transform-panel';
import { ResultPanel } from '@/widgets/result-panel';
import { useAuthStore, useTransformStore } from '@/shared/store';

let introPlayed = false;

const HERO_PLAIN = '글, 보내기 전엔 한 번 더 ';
const HERO_ACCENT = '다듬어야죠';
const HERO_FULL = HERO_PLAIN + HERO_ACCENT;
const TYPE_SPEED = 55;

type Phase = 'typing' | 'pause' | 'settling' | 'ready';

function useIntroAnimation(skip: boolean) {
  const [phase, setPhase] = useState<Phase>(skip ? 'ready' : 'typing');
  const [charCount, setCharCount] = useState(skip ? HERO_FULL.length : 0);

  useEffect(() => {
    if (phase !== 'typing') return;
    const delay = charCount >= HERO_FULL.length ? 0 : TYPE_SPEED;
    const next = charCount >= HERO_FULL.length
      ? () => setPhase('pause')
      : () => setCharCount((c) => c + 1);
    const timer = setTimeout(next, delay);
    return () => clearTimeout(timer);
  }, [charCount, phase]);

  useEffect(() => {
    if (phase !== 'pause') return;
    const timer = setTimeout(() => setPhase('settling'), 400);
    return () => clearTimeout(timer);
  }, [phase]);

  useEffect(() => {
    if (phase !== 'settling') return;
    const timer = setTimeout(() => setPhase('ready'), 950);
    return () => clearTimeout(timer);
  }, [phase]);

  return { phase, charCount };
}

function HeroText({ charCount, className = '' }: { charCount: number; className?: string }) {
  const plainEnd = Math.min(charCount, HERO_PLAIN.length);
  const accentCount = Math.max(0, charCount - HERO_PLAIN.length);

  return (
    <h1 className={`font-bold text-text ${className}`}>
      {charCount > 0 && (
        <>
          {HERO_PLAIN.slice(0, plainEnd)}
          {accentCount > 0 && (
            <span className="text-transparent bg-clip-text bg-gradient-to-r from-accent-deep to-accent">
              {HERO_ACCENT.slice(0, accentCount)}
            </span>
          )}
        </>
      )}
      {charCount < HERO_FULL.length && (
        <span className="inline-block w-[2px] h-[1em] bg-text align-text-bottom ml-0.5 animate-pulse" />
      )}
    </h1>
  );
}

function MainContent({ isReady }: { isReady: boolean }) {
  const { transformedText } = useTransformStore();
  const hasResult = !!transformedText;

  return (
    <div
      className="transition-all duration-500"
      style={{
        opacity: isReady ? 1 : 0,
        transform: isReady ? 'translateY(0)' : 'translateY(16px)',
      }}
    >
      <div
        className={`grid grid-cols-1 gap-6 sm:gap-8 transition-all duration-500 ${hasResult ? 'lg:grid-cols-2' : ''}`}
        style={{
          maxWidth: hasResult ? '100%' : '540px',
          marginInline: hasResult ? '0' : 'auto',
        }}
      >
        <div>
          <TransformPanel />
        </div>
        {hasResult && (
          <div>
            <ResultPanel />
          </div>
        )}
      </div>
    </div>
  );
}

export default function HomePage() {
  const { setLoggedIn } = useAuthStore();
  const [skip] = useState(() => {
    if (introPlayed) return true;
    introPlayed = true;
    return false;
  });
  const { phase, charCount } = useIntroAnimation(skip);

  const heroRef = useRef<HTMLDivElement>(null);
  const [centerOffset, setCenterOffset] = useState({ y: 0, scale: 1 });

  // Measure hero position and compute centering transform BEFORE paint
  useLayoutEffect(() => {
    if (skip || !heroRef.current) return;
    const rect = heroRef.current.getBoundingClientRect();
    const heroCenterY = rect.top + rect.height / 2;
    const screenCenterY = window.innerHeight / 2;
    const offsetY = screenCenterY - heroCenterY;
    const scale = window.innerWidth >= 640 ? 1.5 : 1.2;
    setCenterOffset({ y: offsetY, scale });
  }, [skip]);

  const isBefore = phase === 'typing' || phase === 'pause';
  const isSettling = phase === 'settling';
  const isReady = phase === 'ready';

  // Hero transform: centered+large during typing, transitions to identity during settling
  const heroStyle: React.CSSProperties = isReady
    ? {}
    : {
        position: 'relative',
        zIndex: 50,
        willChange: 'transform',
        transition: isSettling ? 'transform 900ms cubic-bezier(0.4, 0, 0.2, 1)' : 'none',
        transform: isBefore
          ? `translateY(${centerOffset.y}px) scale(${centerOffset.scale})`
          : 'translateY(0) scale(1)',
      };

  return (
    <>
      {/* White backdrop — hides page content behind hero during intro */}
      {!isReady && (
        <div
          className="fixed inset-0 z-40 bg-bg"
          style={{
            transition: isSettling ? 'opacity 800ms ease-in-out' : 'none',
            opacity: isSettling ? 0 : 1,
          }}
        />
      )}

      <Layout>
        <div className="max-w-6xl mx-auto px-4 sm:px-8 pt-8 sm:pt-12 pb-6 sm:pb-8">
          {/* Hero — single element, animates from center to this position */}
          <div ref={heroRef} className="text-center mb-6 sm:mb-8" style={heroStyle}>
            <HeroText charCount={isReady ? HERO_FULL.length : charCount} className="text-xl sm:text-2xl" />
            <p
              className="text-text-secondary mt-2 text-xs sm:text-sm"
              style={{
                opacity: isReady ? 1 : 0,
                transition: 'opacity 500ms ease-in-out',
              }}
            >
              당신의 마지막 말투를 안전하게 다듬어 드립니다
            </p>
            <Link
              to="/admin"
              onClick={() => setLoggedIn({ email: 'admin@politeai.com', loginId: 'admin', name: 'Admin', token: 'dev-token' }, true)}
              className="inline-block mt-2 text-xs text-text-secondary/40 hover:text-text-secondary"
              style={{
                opacity: isReady ? 1 : 0,
                transition: 'opacity 500ms ease-in-out',
              }}
            >
              [DEV] 관리자 페이지
            </Link>
          </div>

          {/* Content — appears after hero settles */}
          <MainContent isReady={isReady} />
        </div>
      </Layout>
    </>
  );
}
