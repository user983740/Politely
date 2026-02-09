interface StatCardProps {
  title: string;
  value: string;
  description?: string;
}

export default function StatCard({ title, value, description }: StatCardProps) {
  return (
    <div className="p-3 sm:p-5 rounded-xl border border-border bg-white">
      <p className="text-xs sm:text-sm text-text-secondary">{title}</p>
      <p className="text-lg sm:text-2xl font-semibold text-text mt-1">{value}</p>
      {description && <p className="text-xs text-text-secondary mt-1">{description}</p>}
    </div>
  );
}
