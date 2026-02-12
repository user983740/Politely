import { Navigate } from 'react-router-dom';
import { Layout } from '@/shared/ui';
import { AdminDashboard } from '@/widgets/admin-dashboard';
import { useAuthStore } from '@/shared/store';

export default function AdminPage() {
  const { isLoggedIn, isAdmin } = useAuthStore();

  if (!isLoggedIn || !isAdmin) {
    return <Navigate to="/" replace />;
  }

  return (
    <Layout>
      <title>관리자 대시보드 - Politely</title>
      <meta name="robots" content="noindex, nofollow" />
      <AdminDashboard />
    </Layout>
  );
}
