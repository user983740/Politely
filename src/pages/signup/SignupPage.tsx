import { useState, useEffect, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Layout, Input, PasswordInput, Button, Checkbox } from '@/shared/ui';
import { useAuthStore } from '@/shared/store';
import { ApiError } from '@/shared/api';
import {
  sendVerificationCode,
  verifyCode,
  checkLoginId,
  signup,
} from '@/features/auth/api';

const PASSWORD_REGEX = /^(?=.*[a-zA-Z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=]).{8,}$/;

function XCircleIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
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

export default function SignupPage() {
  const navigate = useNavigate();
  const setLoggedIn = useAuthStore((s) => s.setLoggedIn);

  // Step tracking
  const [step, setStep] = useState<1 | 2>(1);

  // Step 1 fields
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [emailVerified, setEmailVerified] = useState(false);
  const [cooldown, setCooldown] = useState(0);

  // Step 2 fields
  const [loginId, setLoginId] = useState('');
  const [loginIdChecked, setLoginIdChecked] = useState(false);
  const [loginIdAvailable, setLoginIdAvailable] = useState(false);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [name, setName] = useState('');
  const [privacyAgreed, setPrivacyAgreed] = useState(false);

  // Errors
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);

  // Cooldown timer
  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setTimeout(() => setCooldown(cooldown - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  // Reset loginId check when value changes
  useEffect(() => {
    setLoginIdChecked(false);
    setLoginIdAvailable(false);
  }, [loginId]);

  const clearError = (field: string) => {
    setErrors((prev) => {
      const next = { ...prev };
      delete next[field];
      return next;
    });
  };

  const handleSendCode = async () => {
    if (!email.trim()) {
      setErrors((prev) => ({ ...prev, email: '이메일을 입력해주세요.' }));
      return;
    }
    setLoading(true);
    clearError('email');
    clearError('code');
    try {
      await sendVerificationCode(email);
      setCodeSent(true);
      setCooldown(60);
    } catch (e) {
      if (e instanceof ApiError) {
        setErrors((prev) => ({ ...prev, email: e.message }));
      }
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyCode = async () => {
    if (!code.trim()) {
      setErrors((prev) => ({ ...prev, code: '인증코드를 입력해주세요.' }));
      return;
    }
    setLoading(true);
    clearError('code');
    try {
      await verifyCode(email, code);
      setEmailVerified(true);
      setStep(2);
    } catch (e) {
      if (e instanceof ApiError) {
        setErrors((prev) => ({ ...prev, code: e.message }));
      }
    } finally {
      setLoading(false);
    }
  };

  const handleCheckLoginId = useCallback(async () => {
    if (!loginId.trim() || loginId.length < 3) {
      setErrors((prev) => ({ ...prev, loginId: '아이디는 3자 이상 입력해주세요.' }));
      return;
    }
    clearError('loginId');
    try {
      const res = await checkLoginId(loginId);
      setLoginIdChecked(true);
      setLoginIdAvailable(res.available);
      if (!res.available) {
        setErrors((prev) => ({ ...prev, loginId: '이미 사용 중인 아이디입니다.' }));
      }
    } catch (e) {
      if (e instanceof ApiError) {
        setErrors((prev) => ({ ...prev, loginId: e.message }));
      }
    }
  }, [loginId]);

  const handleSignup = async (e: React.FormEvent) => {
    e.preventDefault();
    const newErrors: Record<string, string> = {};

    if (!loginId.trim() || loginId.length < 3) {
      newErrors.loginId = '아이디는 3자 이상 입력해주세요.';
    } else if (!loginIdChecked || !loginIdAvailable) {
      newErrors.loginId = '아이디 중복검사를 해주세요.';
    }

    if (!PASSWORD_REGEX.test(password)) {
      newErrors.password = '영문, 숫자, 특수문자를 모두 포함하여 8자 이상이어야 합니다.';
    }

    if (password !== confirmPassword) {
      newErrors.confirmPassword = '비밀번호가 일치하지 않습니다.';
    }

    if (!name.trim()) {
      newErrors.name = '이름을 입력해주세요.';
    }

    if (!privacyAgreed) {
      newErrors.privacy = '개인정보 처리방침에 동의해주세요.';
    }

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    setLoading(true);
    setErrors({});
    try {
      const res = await signup({
        email,
        loginId,
        name,
        password,
        privacyAgreed,
      });
      setLoggedIn({
        email: res.email,
        loginId: res.loginId,
        name: res.name,
        token: res.token,
      });
      navigate('/');
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === 'DUPLICATE_LOGIN_ID') {
          setErrors({ loginId: e.message });
        } else {
          setErrors({ general: e.message });
        }
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <Layout>
      <title>회원가입 - Politely</title>
      <meta name="description" content="Politely에 가입하고 한국어 말투 다듬기 서비스를 시작하세요." />
      <link rel="canonical" href="https://politely-ai.com/signup" />
      <div className="max-w-sm mx-auto px-4 pt-12 sm:pt-24 pb-12">
        <h1 className="text-2xl font-bold text-text text-center mb-8">회원가입</h1>

        {step === 1 && (
          <div className="flex flex-col gap-4">
            <div>
              <Input
                label="이메일"
                id="email"
                type="email"
                placeholder="name@example.com"
                value={email}
                onChange={(e) => { setEmail(e.target.value); clearError('email'); }}
                disabled={emailVerified}
              />
              <FieldError message={errors.email} />
            </div>

            {!codeSent ? (
              <Button
                className="w-full py-3"
                onClick={handleSendCode}
                disabled={loading || !email.trim()}
              >
                {loading ? '발송 중...' : '인증코드 발송'}
              </Button>
            ) : !emailVerified ? (
              <>
                <div>
                  <Input
                    label="인증코드"
                    id="code"
                    type="text"
                    placeholder="6자리 숫자 입력"
                    maxLength={6}
                    value={code}
                    onChange={(e) => { setCode(e.target.value); clearError('code'); }}
                  />
                  <FieldError message={errors.code} />
                </div>
                <div className="flex gap-2">
                  <Button
                    className="flex-1 py-3"
                    onClick={handleVerifyCode}
                    disabled={loading || code.length !== 6}
                  >
                    {loading ? '확인 중...' : '인증 확인'}
                  </Button>
                  <Button
                    variant="secondary"
                    className="py-3"
                    onClick={handleSendCode}
                    disabled={loading || cooldown > 0}
                  >
                    {cooldown > 0 ? `${cooldown}초` : '재발송'}
                  </Button>
                </div>
              </>
            ) : null}
          </div>
        )}

        {step === 2 && (
          <form className="flex flex-col gap-4" onSubmit={handleSignup}>
            <div className="text-sm text-text-secondary bg-surface rounded-lg px-3 py-2">
              {email} (인증 완료)
            </div>

            <div>
              <div className="flex gap-2 items-end">
                <div className="flex-1">
                  <Input
                    label="아이디"
                    id="loginId"
                    type="text"
                    placeholder="3자 이상"
                    maxLength={30}
                    value={loginId}
                    onChange={(e) => { setLoginId(e.target.value); clearError('loginId'); }}
                  />
                </div>
                <Button
                  type="button"
                  variant="secondary"
                  className="py-2 shrink-0"
                  onClick={handleCheckLoginId}
                  disabled={loginId.length < 3}
                >
                  중복검사
                </Button>
              </div>
              {loginIdChecked && loginIdAvailable && !errors.loginId && (
                <p className="text-sm text-green-600 mt-1">사용 가능한 아이디입니다.</p>
              )}
              <FieldError message={errors.loginId} />
            </div>

            <div>
              <PasswordInput
                label="비밀번호"
                id="password"
                placeholder="영문, 숫자, 특수문자 포함 8자 이상"
                value={password}
                onChange={(e) => { setPassword(e.target.value); clearError('password'); }}
              />
              <FieldError message={errors.password} />
            </div>

            <div>
              <PasswordInput
                label="비밀번호 확인"
                id="confirmPassword"
                placeholder="비밀번호를 다시 입력하세요"
                value={confirmPassword}
                onChange={(e) => { setConfirmPassword(e.target.value); clearError('confirmPassword'); }}
              />
              <FieldError message={errors.confirmPassword} />
            </div>

            <div>
              <Input
                label="이름"
                id="name"
                type="text"
                placeholder="이름을 입력하세요"
                maxLength={50}
                value={name}
                onChange={(e) => { setName(e.target.value); clearError('name'); }}
              />
              <FieldError message={errors.name} />
            </div>

            <div>
              <Checkbox
                id="privacy"
                label={
                  <>
                    <Link to="/privacy" target="_blank" className="text-accent hover:underline">
                      개인정보 처리방침
                    </Link>
                    에 동의합니다
                  </>
                }
                checked={privacyAgreed}
                onChange={(e) => { setPrivacyAgreed(e.target.checked); clearError('privacy'); }}
              />
              <FieldError message={errors.privacy} />
            </div>

            <FieldError message={errors.general} />

            <Button
              type="submit"
              className="w-full py-3 mt-2"
              disabled={loading}
            >
              {loading ? '가입 중...' : '회원가입'}
            </Button>
          </form>
        )}

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
