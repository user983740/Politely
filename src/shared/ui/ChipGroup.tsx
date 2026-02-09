import Chip from './Chip';

interface ChipOption {
  key: string;
  label: string;
}

interface ChipGroupProps {
  label?: string;
  hint?: string;
  options: readonly ChipOption[];
  selected: string | string[];
  onSelect: (key: string) => void;
}

export default function ChipGroup({ label, hint, options, selected, onSelect }: ChipGroupProps) {
  const isSelected = (key: string) =>
    Array.isArray(selected) ? selected.includes(key) : selected === key;

  return (
    <div className="flex flex-col gap-2">
      {(label || hint) && (
        <div className="flex items-baseline gap-2">
          {label && <span className="text-sm font-medium text-text">{label}</span>}
          {hint && <span className="text-xs text-text-secondary">{hint}</span>}
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        {options.map((option) => (
          <Chip
            key={option.key}
            label={option.label}
            selected={isSelected(option.key)}
            onClick={() => onSelect(option.key)}
          />
        ))}
      </div>
    </div>
  );
}
