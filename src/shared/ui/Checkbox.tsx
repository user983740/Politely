import type { InputHTMLAttributes, ReactNode } from 'react';

interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: ReactNode;
}

export default function Checkbox({ label, id, className = '', ...props }: CheckboxProps) {
  return (
    <label htmlFor={id} className={`flex items-start gap-2 cursor-pointer ${className}`}>
      <input
        id={id}
        type="checkbox"
        className="mt-0.5 w-4 h-4 accent-accent cursor-pointer"
        {...props}
      />
      <span className="text-sm text-text">{label}</span>
    </label>
  );
}
