import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Layout, Input, PasswordInput, Button } from '@/shared/ui';
import { useAuthStore } from '@/shared/store';
import { ApiError } from '@/shared/api';
import { login } from '@/features/auth/api';

function XCircleIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 20 20" fill="currentColor">
      <path
        fillRule="evenodd"
        d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.28 7.22a.75.75 0 00-1.06 1.06L8.94 10l-1.72 1.72a.75.75 0 101.06 1.06L10 11.06l1.72 1.72a.75.75 0 101.06-1.06L11.06 10l1.72-1.72a.75.75 0 00-1.06-1.06L10 8.94 8.28 7.22z"
        clipRule="evenodd"
      />
    </svg>
  );
}

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <p className="flex items-center gap-1 text-sm text-red-500 mt-1">
      <XCircleIcon className="w-4 h-4 shrink-0" />
      {message}
    </p>
  );
}

export default function LoginPage() {
  const navigate = useNavigate();
  const setLoggedIn = useAuthStore((s) => s.setLoggedIn);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!email.trim() || !password.trim()) {
      setError('이메일과 비밀번호를 입력해주세요.');
      return;
    }

    setLoading(true);
    try {
      const res = await login({ email, password });
      setLoggedIn({
        email: res.email,
        loginId: res.loginId,
        name: res.name,
        token: res.token,
      });
      navigate('/');
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.message);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <Layout>
      <div className="max-w-sm mx-auto px-4 pt-12 sm:pt-24 pb-12">
        <h1 className="text-2xl font-bold text-text text-center mb-8">로그인</h1>
        <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
          <Input
            label="이메일"
            id="email"
            type="email"
            placeholder="name@example.com"
            value={email}
            onChange={(e) => { setEmail(e.target.value); setError(''); }}
          />
          <PasswordInput
            label="비밀번호"
            id="password"
            placeholder="비밀번호를 입력하세요"
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(''); }}
          />
          <FieldError message={error} />
          <Button
            type="submit"
            className="w-full py-3 mt-2"
            disabled={loading}
          >
            {loading ? '로그인 중...' : '로그인'}
          </Button>
        </form>
        <p className="text-sm text-text-secondary text-center mt-6">
          아직 계정이 없으신가요?{' '}
          <Link to="/signup" className="text-accent hover:underline">
            회원가입
          </Link>
        </p>
      </div>
    </Layout>
  );
}
