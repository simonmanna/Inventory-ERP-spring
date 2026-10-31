import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/auth.store';

export function ProtectedRoute() {
  const accessToken = useAuthStore((s) => s.accessToken);
  const permissions = useAuthStore((s) => s.permissions);
  const { pathname } = useLocation();
  if (!accessToken) {
    return <Navigate to="/login" replace />;
  }
  // A customer portal login (its only permission) has no business in the staff app.
  if (permissions.length === 1 && permissions[0] === 'portal:customer' && !pathname.startsWith('/portal')) {
    return <Navigate to="/portal" replace />;
  }
  return <Outlet />;
}
