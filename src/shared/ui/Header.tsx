import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '@/shared/store';

export default function Header() {
  const { isLoggedIn, isAdmin, loginId, setLoggedOut } = useAuthStore();
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <header className="border-b border-border bg-white">
      <div className="max-w-5xl mx-auto px-4 h-14 flex items-center justify-between">
        <Link to="/" className="text-lg font-bold text-transparent bg-clip-text bg-gradient-to-r from-accent-deep to-accent">
          PoliteAi
        </Link>

        {/* Desktop nav */}
        <nav className="hidden sm:flex items-center gap-4 text-sm">
          {isLoggedIn ? (
            <>
              <span className="text-text-secondary truncate max-w-32">{loginId}</span>
              {isAdmin && (
                <Link to="/admin" className="text-text-secondary hover:text-text transition-colors">
                  Admin
                </Link>
              )}
              <button
                onClick={setLoggedOut}
                className="text-text-secondary hover:text-text transition-colors cursor-pointer"
              >
                로그아웃
              </button>
            </>
          ) : (
            <>
              <Link to="/login" className="text-text-secondary hover:text-text transition-colors">
                로그인
              </Link>
              <Link
                to="/signup"
                className="px-3 py-1.5 bg-accent text-white rounded-lg hover:bg-accent-hover transition-colors"
              >
                회원가입
              </Link>
            </>
          )}
        </nav>

        {/* Mobile hamburger */}
        <button
          onClick={() => setMenuOpen(!menuOpen)}
          className="sm:hidden p-2 text-text-secondary hover:text-text transition-colors cursor-pointer"
          aria-label="메뉴 열기"
        >
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
            {menuOpen ? (
              <>
                <line x1="4" y1="4" x2="16" y2="16" />
                <line x1="16" y1="4" x2="4" y2="16" />
              </>
            ) : (
              <>
                <line x1="3" y1="5" x2="17" y2="5" />
                <line x1="3" y1="10" x2="17" y2="10" />
                <line x1="3" y1="15" x2="17" y2="15" />
              </>
            )}
          </svg>
        </button>
      </div>

      {/* Mobile menu dropdown */}
      {menuOpen && (
        <nav className="sm:hidden border-t border-border bg-white px-4 py-3 flex flex-col gap-3 text-sm">
          {isLoggedIn ? (
            <>
              <span className="text-text-secondary truncate">{loginId}</span>
              {isAdmin && (
                <Link to="/admin" onClick={() => setMenuOpen(false)} className="text-text-secondary hover:text-text transition-colors">
                  Admin
                </Link>
              )}
              <button
                onClick={() => { setLoggedOut(); setMenuOpen(false); }}
                className="text-text-secondary hover:text-text transition-colors cursor-pointer text-left"
              >
                로그아웃
              </button>
            </>
          ) : (
            <>
              <Link to="/login" onClick={() => setMenuOpen(false)} className="text-text-secondary hover:text-text transition-colors">
                로그인
              </Link>
              <Link
                to="/signup"
                onClick={() => setMenuOpen(false)}
                className="text-center px-3 py-2 bg-accent text-white rounded-lg hover:bg-accent-hover transition-colors"
              >
                회원가입
              </Link>
            </>
          )}
        </nav>
      )}
    </header>
  );
}
