import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Layout, Input, Button } from '@/shared/ui';

export default function SignupPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  return (
    <Layout>
      <div className="max-w-sm mx-auto px-4 pt-12 sm:pt-24">
        <h1 className="text-2xl font-bold text-text text-center mb-8">회원가입</h1>
        <form className="flex flex-col gap-4" onSubmit={(e) => e.preventDefault()}>
          <Input
            label="이메일"
            id="email"
            type="email"
            placeholder="name@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <Input
            label="비밀번호"
            id="password"
            type="password"
            placeholder="8자 이상 입력하세요"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <Input
            label="비밀번호 확인"
            id="confirm-password"
            type="password"
            placeholder="비밀번호를 다시 입력하세요"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
          />
          <Button className="w-full py-3 mt-2">회원가입</Button>
        </form>
        <p className="text-sm text-text-secondary text-center mt-6">
          이미 계정이 있으신가요?{' '}
          <Link to="/login" className="text-accent hover:underline">
            로그인
          </Link>
        </p>
      </div>
    </Layout>
  );
}
