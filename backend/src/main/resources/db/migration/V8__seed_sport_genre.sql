-- The Sport category is available to editors before any catalog title carries it. Genres are
-- otherwise created only by the catalog manifest importer, and there is no admin create-genre
-- command, so the category needs a seeded row. Additive and conflict-tolerant: a manifest that
-- later introduces the same slug keeps this row intact. Nothing already applied is edited.

INSERT INTO genre(slug, name) VALUES ('sport', 'Sport')
ON CONFLICT (slug) DO NOTHING;
