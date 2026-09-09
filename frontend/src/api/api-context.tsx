import { createContext, type ReactNode, useContext } from 'react';
import { streamingApi, type StreamingApi } from './streaming-api';

const ApiContext = createContext<StreamingApi>(streamingApi);

export function ApiProvider({ api, children }: { api: StreamingApi; children: ReactNode }) {
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>;
}

export function useApi(): StreamingApi {
  return useContext(ApiContext);
}
