import { useState } from 'react';
import type { AdminMovie, Genre, MovieInput } from '../../api/admin-api';

const MATURITY_RATINGS = ['G', 'PG', 'PG-13', 'R', 'NC-17', 'NR'] as const;
const MAX_GENRES = 10;

type Maturity = typeof MATURITY_RATINGS[number];
export type SaveAction = 'draft' | 'continue';

interface MovieFormProps {
  initial: AdminMovie | null;
  genres: Genre[];
  readOnly: boolean;
  slugLocked: boolean;
  saving: boolean;
  onSubmit(input: MovieInput, action: SaveAction): Promise<void>;
}

interface FormValues {
  title: string;
  slug: string;
  synopsis: string;
  releaseYear: number;
  maturityRating: Maturity;
  genreIds: number[];
  featured: boolean;
}

function initialValues(movie: AdminMovie | null): FormValues {
  return {
    title: movie?.title ?? '',
    slug: movie?.slug ?? '',
    synopsis: movie?.synopsis ?? '',
    releaseYear: movie?.releaseYear ?? new Date().getFullYear(),
    maturityRating: (movie?.maturityRating as Maturity) ?? 'NR',
    genreIds: movie?.genreIds ?? [],
    featured: movie?.featured ?? false,
  };
}

export function MovieForm({ initial, genres, readOnly, slugLocked, saving, onSubmit }: MovieFormProps) {
  const [values, setValues] = useState<FormValues>(() => initialValues(initial));
  const [error, setError] = useState<string | null>(null);

  const slugDisabled = slugLocked || readOnly;
  const atGenreLimit = values.genreIds.length >= MAX_GENRES;

  function setField<K extends keyof FormValues>(field: K, value: FormValues[K]) {
    setValues((current) => ({ ...current, [field]: value }));
    setError(null);
  }

  function toggleGenre(genreId: number, checked: boolean) {
    setValues((current) => ({
      ...current,
      genreIds: checked
        ? [...current.genreIds, genreId]
        : current.genreIds.filter((id) => id !== genreId),
    }));
    setError(null);
  }

  async function submit(action: SaveAction) {
    const title = values.title.trim();
    const slug = values.slug.trim();
    if (!title) {
      setError('Title is required.');
      return;
    }
    if (!slug || !/^[a-z0-9]+(-[a-z0-9]+)*$/.test(slug)) {
      setError('Slug must use lowercase letters, digits and single hyphens.');
      return;
    }
    if (!Number.isInteger(values.releaseYear) || values.releaseYear < 1888 || values.releaseYear > 2200) {
      setError('Release year must be between 1888 and 2200.');
      return;
    }
    if (values.genreIds.length > MAX_GENRES) {
      setError(`Choose at most ${MAX_GENRES} genres.`);
      return;
    }
    await onSubmit({
      title,
      slug,
      synopsis: values.synopsis,
      releaseYear: values.releaseYear,
      maturityRating: values.maturityRating,
      genreIds: values.genreIds,
      featured: values.featured,
    }, action);
  }

  return (
    <form
      className="admin-form"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void submit('draft');
      }}
    >
      <div className="admin-form__grid">
        <label className="admin-field" htmlFor="field-title">
          <span>Title</span>
          <input
            id="field-title"
            maxLength={200}
            onChange={(event) => setField('title', event.target.value)}
            disabled={readOnly}
            required
            value={values.title}
          />
        </label>

        <label className="admin-field" htmlFor="field-slug">
          <span>Slug</span>
          <input
            id="field-slug"
            maxLength={120}
            onChange={(event) => setField('slug', event.target.value)}
            disabled={slugDisabled}
            required
            value={values.slug}
          />
        </label>

        <label className="admin-field admin-field--wide" htmlFor="field-synopsis">
          <span>Synopsis</span>
          <textarea
            id="field-synopsis"
            maxLength={10000}
            onChange={(event) => setField('synopsis', event.target.value)}
            disabled={readOnly}
            rows={5}
            value={values.synopsis}
          />
        </label>

        <label className="admin-field" htmlFor="field-year">
          <span>Release year</span>
          <input
            id="field-year"
            type="number"
            min={1888}
            max={2200}
            onChange={(event) => setField('releaseYear', Number(event.target.value))}
            disabled={readOnly}
            value={values.releaseYear}
          />
        </label>

        <label className="admin-field" htmlFor="field-maturity">
          <span>Maturity rating</span>
          <select
            id="field-maturity"
            onChange={(event) => setField('maturityRating', event.target.value as Maturity)}
            disabled={readOnly}
            value={values.maturityRating}
          >
            {MATURITY_RATINGS.map((rating) => <option key={rating} value={rating}>{rating}</option>)}
          </select>
        </label>
      </div>

      <fieldset className="admin-genres">
        <legend>Genres</legend>
        <div className="admin-genres__list">
          {genres.map((genre) => {
            const checked = values.genreIds.includes(genre.id);
            return (
              <label
                className={checked ? 'admin-genre admin-genre--checked' : 'admin-genre'}
                htmlFor={`genre-${genre.id}`}
                key={genre.id}
              >
                <input
                  id={`genre-${genre.id}`}
                  type="checkbox"
                  checked={checked}
                  disabled={readOnly || (!checked && atGenreLimit)}
                  onChange={(event) => toggleGenre(genre.id, event.target.checked)}
                />
                <span>{genre.name}</span>
              </label>
            );
          })}
        </div>
      </fieldset>

      <label className="admin-field admin-field--check" htmlFor="field-featured">
        <input
          id="field-featured"
          type="checkbox"
          checked={values.featured}
          disabled={readOnly}
          onChange={(event) => setField('featured', event.target.checked)}
        />
        <span>Featured</span>
      </label>

      {error ? <p className="admin-form__error" role="alert">{error}</p> : null}

      <div className="admin-form__actions">
        <button className="button button--primary" type="submit" disabled={readOnly || saving}>
          {saving ? 'Saving…' : 'Save draft'}
        </button>
        <button
          className="button button--ghost"
          type="button"
          disabled={readOnly || saving}
          onClick={() => void submit('continue')}
        >
          Save and continue
        </button>
      </div>
    </form>
  );
}
