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
      <AdminDashboard />
    </Layout>
  );
}
