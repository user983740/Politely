import { StatCard } from '@/shared/ui';

const DAILY_TRANSFORMS = [
  { date: '2026-02-03', count: 312 },
  { date: '2026-02-04', count: 287 },
  { date: '2026-02-05', count: 356 },
  { date: '2026-02-06', count: 401 },
  { date: '2026-02-07', count: 389 },
  { date: '2026-02-08', count: 445 },
  { date: '2026-02-09', count: 423 },
];

const PERSONA_DISTRIBUTION = [
  { label: '직장 상사', percent: 35 },
  { label: '고객', percent: 25 },
  { label: '교수', percent: 15 },
  { label: '학부모', percent: 10 },
  { label: '동료', percent: 10 },
  { label: '공식 기관', percent: 5 },
];

const CONTEXT_DISTRIBUTION = [
  { label: '요청', percent: 28 },
  { label: '일정 지연', percent: 18 },
  { label: '독촉', percent: 15 },
  { label: '거절', percent: 12 },
  { label: '사과', percent: 10 },
  { label: '항의', percent: 8 },
  { label: '공지', percent: 5 },
  { label: '피드백', percent: 4 },
];

const TONE_DISTRIBUTION = [
  { label: '중립', percent: 20 },
  { label: '공손', percent: 45 },
  { label: '매우 공손', percent: 35 },
];

const RETENTION = [
  { period: 'D1', rate: '42%' },
  { period: 'D3', rate: '28%' },
  { period: 'D7', rate: '18%' },
];

const HEATMAP_PERSONAS = ['직장 상사', '고객', '교수', '학부모', '동료', '공식 기관'];
const HEATMAP_CONTEXTS = ['요청', '일정 지연', '독촉', '거절', '사과', '항의', '공지', '피드백'];
const HEATMAP_DATA = [
  [85, 45, 62, 30, 28, 15, 12, 8],
  [60, 35, 48, 42, 22, 38, 5, 10],
  [40, 18, 12, 8, 15, 5, 22, 25],
  [25, 12, 8, 5, 18, 28, 15, 5],
  [20, 15, 22, 10, 8, 5, 8, 12],
  [10, 5, 3, 8, 4, 2, 18, 2],
];

const API_METRICS = [
  { label: '평균 응답시간', value: '1.2s' },
  { label: 'P95 응답시간', value: '2.8s' },
  { label: 'P99 응답시간', value: '4.1s' },
  { label: '오늘 에러율', value: '0.3%' },
];

function getHeatColor(value: number): string {
  if (value >= 60) return 'bg-accent text-white';
  if (value >= 40) return 'bg-accent/70 text-white';
  if (value >= 20) return 'bg-accent/40 text-white';
  if (value >= 10) return 'bg-accent/20 text-text';
  return 'bg-accent/5 text-text-secondary';
}

function BarChart({ data }: { data: { label: string; percent: number }[] }) {
  return (
    <div className="flex flex-col gap-2">
      {data.map((item) => (
        <div key={item.label} className="flex items-center gap-2 sm:gap-3">
          <span className="text-xs text-text-secondary w-16 sm:w-28 text-right shrink-0 truncate">{item.label}</span>
          <div className="flex-1 h-5 sm:h-6 bg-surface rounded overflow-hidden">
            <div
              className="h-full bg-accent rounded transition-all"
              style={{ width: `${item.percent}%` }}
            />
          </div>
          <span className="text-xs text-text-secondary w-10">{item.percent}%</span>
        </div>
      ))}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-border bg-white p-4 sm:p-6">
      <h3 className="text-sm font-medium text-text mb-3 sm:mb-4">{title}</h3>
      {children}
    </section>
  );
}

export default function AdminDashboard() {
  return (
    <div className="max-w-6xl mx-auto px-4 py-6 sm:py-8 flex flex-col gap-6 sm:gap-8">
      <h2 className="text-lg sm:text-xl font-semibold text-text">Admin Dashboard</h2>

      {/* Overview Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
        <StatCard title="총 사용자" value="1,247명" />
        <StatCard title="오늘 DAU" value="156명" />
        <StatCard title="오늘 변환 수" value="423건" />
        <StatCard title="오늘 API 비용" value="$12.40" />
      </div>

      {/* Daily Transform Trend */}
      <Section title="일별 변환 추이 (최근 7일)">
        <div className="overflow-x-auto -mx-4 px-4 sm:mx-0 sm:px-0">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border">
                <th className="text-left py-2 text-text-secondary font-medium">날짜</th>
                <th className="text-right py-2 text-text-secondary font-medium">변환 수</th>
              </tr>
            </thead>
            <tbody>
              {DAILY_TRANSFORMS.map((row) => (
                <tr key={row.date} className="border-b border-border last:border-b-0">
                  <td className="py-2 text-text">{row.date}</td>
                  <td className="py-2 text-text text-right">{row.count}건</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* Distribution Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Section title="페르소나 분포">
          <BarChart data={PERSONA_DISTRIBUTION} />
        </Section>
        <Section title="상황 분포">
          <BarChart data={CONTEXT_DISTRIBUTION} />
        </Section>
      </div>

      <Section title="톤 레벨 분포">
        <BarChart data={TONE_DISTRIBUTION} />
      </Section>

      {/* Retention */}
      <Section title="리텐션">
        <div className="flex flex-wrap gap-6 sm:gap-8">
          {RETENTION.map((item) => (
            <div key={item.period} className="text-center min-w-16">
              <p className="text-xl sm:text-2xl font-semibold text-text">{item.rate}</p>
              <p className="text-xs text-text-secondary mt-1">{item.period}</p>
            </div>
          ))}
        </div>
      </Section>

      {/* Persona × Context Heatmap */}
      <Section title="페르소나 × 상황 히트맵">
        <div className="overflow-x-auto -mx-4 px-4 sm:mx-0 sm:px-0">
          <table className="text-xs min-w-[520px] w-full">
            <thead>
              <tr>
                <th className="p-1.5 sm:p-2 text-left text-text-secondary font-medium" />
                {HEATMAP_CONTEXTS.map((ctx) => (
                  <th key={ctx} className="p-1.5 sm:p-2 text-center text-text-secondary font-medium whitespace-nowrap">
                    {ctx}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {HEATMAP_PERSONAS.map((persona, i) => (
                <tr key={persona}>
                  <td className="p-1.5 sm:p-2 text-text-secondary font-medium whitespace-nowrap">{persona}</td>
                  {HEATMAP_DATA[i].map((val, j) => (
                    <td key={j} className="p-0.5 sm:p-1">
                      <div className={`rounded p-1.5 sm:p-2 text-center ${getHeatColor(val)}`}>
                        {val}
                      </div>
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* API Performance */}
      <Section title="API 성능 지표">
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
          {API_METRICS.map((metric) => (
            <div key={metric.label} className="text-center">
              <p className="text-lg sm:text-xl font-semibold text-text">{metric.value}</p>
              <p className="text-xs text-text-secondary mt-1">{metric.label}</p>
            </div>
          ))}
        </div>
      </Section>
    </div>
  );
}
