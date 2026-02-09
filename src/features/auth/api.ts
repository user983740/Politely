import { apiClient } from '@/shared/api';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface SignupRequest {
  email: string;
  loginId: string;
  name: string;
  password: string;
  privacyAgreed: boolean;
}

export interface AuthResponse {
  token: string;
  email: string;
  loginId: string;
  name: string;
}

export interface CheckLoginIdResponse {
  available: boolean;
}

export function login(req: LoginRequest): Promise<AuthResponse> {
  return apiClient.post('/auth/login', req);
}

export function signup(req: SignupRequest): Promise<AuthResponse> {
  return apiClient.post('/auth/signup', req);
}

export function sendVerificationCode(email: string): Promise<{ message: string }> {
  return apiClient.post('/auth/email/send-code', { email });
}

export function verifyCode(email: string, code: string): Promise<{ message: string }> {
  return apiClient.post('/auth/email/verify-code', { email, code });
}

export function checkLoginId(loginId: string): Promise<CheckLoginIdResponse> {
  return apiClient.post('/auth/check-login-id', { loginId });
}
