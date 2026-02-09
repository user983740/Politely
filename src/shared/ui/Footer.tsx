import { Link } from 'react-router-dom';

export default function Footer() {
  return (
    <footer className="border-t border-border bg-white py-4">
      <div className="max-w-5xl mx-auto px-4 flex flex-col sm:flex-row items-center justify-between gap-2 text-xs text-text-secondary">
        <span>&copy; 2026 PoliteAi</span>
        <Link to="/privacy" className="hover:text-text transition-colors">
          개인정보 처리방침
        </Link>
      </div>
    </footer>
  );
}
