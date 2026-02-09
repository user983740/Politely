import { create } from 'zustand';

interface AuthState {
  isLoggedIn: boolean;
  isAdmin: boolean;
  email: string | null;
  loginId: string | null;
  name: string | null;
  setLoggedIn: (data: { email: string; loginId: string; name: string; token: string }, isAdmin?: boolean) => void;
  setLoggedOut: () => void;
  initFromStorage: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  isLoggedIn: false,
  isAdmin: false,
  email: null,
  loginId: null,
  name: null,
  setLoggedIn: ({ email, loginId, name, token }, isAdmin = false) => {
    localStorage.setItem('token', token);
    localStorage.setItem('email', email);
    localStorage.setItem('loginId', loginId);
    localStorage.setItem('name', name);
    set({ isLoggedIn: true, email, loginId, name, isAdmin });
  },
  setLoggedOut: () => {
    localStorage.removeItem('token');
    localStorage.removeItem('email');
    localStorage.removeItem('loginId');
    localStorage.removeItem('name');
    set({ isLoggedIn: false, isAdmin: false, email: null, loginId: null, name: null });
  },
  initFromStorage: () => {
    const token = localStorage.getItem('token');
    const email = localStorage.getItem('email');
    const loginId = localStorage.getItem('loginId');
    const name = localStorage.getItem('name');
    if (token && email && loginId && name) {
      set({ isLoggedIn: true, email, loginId, name });
    }
  },
}));
