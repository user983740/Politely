import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { HomePage } from '@/pages/home';
import { LoginPage } from '@/pages/login';
import { SignupPage } from '@/pages/signup';
import { AdminPage } from '@/pages/admin';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/admin" element={<AdminPage />} />
      </Routes>
    </BrowserRouter>
  );
}
