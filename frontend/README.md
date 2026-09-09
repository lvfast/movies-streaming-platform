# LVFAST Cinema frontend

React/Vite frontend for the LVFAST media streaming demo. It consumes the generated client in `src/api/generated`, derived from `../docs/api/openapi.yaml`.

## Prerequisites

Use Node.js 22.22.2 or newer with npm. For live API and media requests, run compatible local services on ports `8080` and `8081`, or use the repository-root Compose workflow instead.

## Commands

```sh
npm ci
npm run dev
npm test
npm run build
npm run check:api
```

The Vite development server listens on <http://localhost:5173>. It proxies `/api` to `http://localhost:8080` and `/media` to `http://localhost:8081`.

`npm run generate:api` regenerates the checked-in client. `npm run check:api` verifies that regeneration produces no diff.

`VITE_API_BASE_URL` can override the default same-origin `/api/v1` base URL. Access tokens stay in module memory; refresh tokens remain in the backend-managed HttpOnly cookie.
