# LVFAST Cinema frontend

React/Vite integration shell for the media streaming MVP. The UI is independently authored and consumes the generated client in `src/api/generated` from `docs/api/openapi.yaml`.

## Commands

```sh
npm install
npm run generate:api
npm test
npm run dev
npm run build
```

The development server listens on `http://localhost:5173` and proxies `/api` to the backend on port `8080` and `/media` to the local media server on port `8081`.

`VITE_API_BASE_URL` can override the default same-origin `/api/v1` base URL. Access tokens stay in module memory; refresh tokens remain in the backend-managed HttpOnly cookie.
