import type { TextareaHTMLAttributes } from 'react';

interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string;
  maxLength?: number;
  value?: string;
}

export default function Textarea({ label, id, maxLength, value = '', className = '', ...props }: TextareaProps) {
  return (
    <div className="flex flex-col gap-1.5">
      {label && (
        <label htmlFor={id} className="text-sm font-medium text-text">
          {label}
        </label>
      )}
      <textarea
        id={id}
        maxLength={maxLength}
        value={value}
        className={`w-full px-3 py-2 rounded-lg border border-border bg-white text-sm text-text placeholder:text-text-secondary/50 focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent transition-colors resize-none ${className}`}
        {...props}
      />
      {maxLength && (
        <span className="text-xs text-text-secondary text-right">
          {value.length}/{maxLength}
        </span>
      )}
    </div>
  );
}
