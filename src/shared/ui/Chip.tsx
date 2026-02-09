interface ChipProps {
  label: string;
  selected?: boolean;
  onClick?: () => void;
}

export default function Chip({ label, selected = false, onClick }: ChipProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`px-3 py-1.5 rounded-full text-sm font-medium transition-all cursor-pointer ${
        selected
          ? 'bg-accent text-white shadow-sm shadow-accent/25 scale-[1.02]'
          : 'bg-surface text-text-secondary hover:bg-accent-light hover:text-accent'
      }`}
    >
      {label}
    </button>
  );
}
