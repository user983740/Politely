import { apiClient } from '@/shared/api';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface SignupRequest {
  email: string;
  password: string;
}

export interface AuthResponse {
  token: string;
}

export function login(req: LoginRequest): Promise<AuthResponse> {
  return apiClient.post('/v1/auth/login', req);
}

export function signup(req: SignupRequest): Promise<AuthResponse> {
  return apiClient.post('/v1/auth/signup', req);
}
