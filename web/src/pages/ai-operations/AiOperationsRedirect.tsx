import { Navigate, useLocation } from 'react-router-dom';
import { AI_OPS_PATH } from './access';

/** Sends an old /alerts or /loss-prevention address to its tab of AI Operations Intelligence, keeping the query. */
export function AiOperationsRedirect({ tab }: { tab: string }) {
  const { search, hash } = useLocation();
  const params = new URLSearchParams(search);
  if (!params.has('tab')) params.set('tab', tab);
  return <Navigate to={`${AI_OPS_PATH}?${params.toString()}${hash}`} replace />;
}
