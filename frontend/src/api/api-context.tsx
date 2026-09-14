import { createContext, type ReactNode, useContext } from 'react';
import { streamingApi, type StreamingApi } from './streaming-api';
import type { AdminApi } from './admin-api';

const ApiContext = createContext<StreamingApi>(streamingApi);

const AdminApiContext = createContext<AdminApi | null>(null);

export function ApiProvider({ api, children }: { api: StreamingApi; children: ReactNode }) {
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>;
}

export function useApi(): StreamingApi {
  return useContext(ApiContext);
}

export function AdminApiProvider({ api, children }: { api: AdminApi; children: ReactNode }) {
  return <AdminApiContext.Provider value={api}>{children}</AdminApiContext.Provider>;
}

export function useAdminApi(): AdminApi {
  const api = useContext(AdminApiContext);
  if (!api) throw new Error('useAdminApi must be used inside AdminApiProvider');
  return api;
}
