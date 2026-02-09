import { create } from 'zustand';

interface AuthState {
  isLoggedIn: boolean;
  isAdmin: boolean;
  email: string | null;
  setLoggedIn: (email: string, isAdmin?: boolean) => void;
  setLoggedOut: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  isLoggedIn: false,
  isAdmin: false,
  email: null,
  setLoggedIn: (email, isAdmin = false) => set({ isLoggedIn: true, email, isAdmin }),
  setLoggedOut: () => set({ isLoggedIn: false, isAdmin: false, email: null }),
}));
