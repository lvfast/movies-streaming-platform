import { Navigate, Route, Routes } from 'react-router-dom';
import { adminApi, type AdminApi } from '../api/admin-api';
import './admin.css';
import { AdminApiProvider } from '../api/api-context';
import { AdminGuard } from './admin-guard';
import { AdminLayout } from './admin-layout';
import { MovieEditorPage } from './editor/movie-editor-page';
import { FilmLibraryPage } from './library/film-library-page';
import { JobsPage } from './jobs/jobs-page';
import { AuditPage } from './audit/audit-page';
import { UploadPage } from './uploads/upload-page';
import { MovieReviewPage } from './review/movie-review-page';

export function AdminRoutes({ api = adminApi }: { api?: AdminApi }) {
  return (
    <AdminApiProvider api={api}>
      <AdminGuard>
        <Routes>
          <Route element={<AdminLayout />}>
            <Route index element={<Navigate to="movies" replace />} />
            <Route path="movies" element={<FilmLibraryPage />} />
            <Route path="movies/new" element={<MovieEditorPage />} />
            <Route path="movies/:movieId" element={<MovieEditorPage />} />
            <Route path="movies/:movieId/uploads" element={<UploadPage />} />
            <Route path="movies/:movieId/review" element={<MovieReviewPage />} />
            <Route path="jobs" element={<JobsPage />} />
            <Route path="jobs/:jobId" element={<JobsPage />} />
            <Route path="audit" element={<AuditPage />} />
            <Route path="*" element={<Navigate to="movies" replace />} />
          </Route>
        </Routes>
      </AdminGuard>
    </AdminApiProvider>
  );
}
