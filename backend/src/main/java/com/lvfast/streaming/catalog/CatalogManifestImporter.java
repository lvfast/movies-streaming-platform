package com.lvfast.streaming.catalog;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name = "app.catalog.import-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogManifestImporter implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Resource manifest;
    private final CatalogRevisionRepository revisions;

    CatalogManifestImporter(JdbcTemplate jdbc, ObjectMapper json,
            @Value("${app.catalog.manifest}") Resource manifest, CatalogRevisionRepository revisions) {
        this.jdbc = jdbc;
        this.json = json;
        this.manifest = manifest;
        this.revisions = revisions;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        importConfiguredManifest();
    }

    @Transactional
    public boolean importConfiguredManifest() {
        try {
            byte[] bytes;
            try (InputStream input = manifest.getInputStream()) {
                bytes = input.readAllBytes();
            }
            return importManifestBytes(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read catalog manifest", exception);
        }
    }

    @Transactional
    public boolean importManifest(byte[] bytes) {
        try {
            return importManifestBytes(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read catalog manifest", exception);
        }
    }

    private boolean importManifestBytes(byte[] bytes) throws IOException {
        Manifest data = json.readValue(bytes, Manifest.class);
        if (data.version() == null || data.version().isBlank() || data.movies() == null) {
            throw new IllegalStateException("Catalog manifest requires version and movies");
        }
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) resultSet -> null,
                data.version());
        String hash = sha256(bytes);
        List<String> existing = jdbc.queryForList(
                "select manifest_sha256 from catalog_import where manifest_version=?", String.class, data.version());
        if (!existing.isEmpty()) {
            if (!existing.getFirst().equals(hash)) {
                throw new IllegalStateException("Catalog manifest version content changed: " + data.version());
            }
            return false;
        }
        for (ManifestMovie movie : data.movies()) importMovie(movie);
        jdbc.update("insert into catalog_import(manifest_version, manifest_sha256) values (?, ?)", data.version(), hash);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                revisions.bump();
            }
        });
        return true;
    }

    private void importMovie(ManifestMovie movie) {
        List<String> modes = jdbc.queryForList(
                "select management_mode from movie where id=?", String.class, movie.id());
        if (!modes.isEmpty() && "MANAGED".equals(modes.getFirst())) {
            // An editor already adopted this row; the manifest must not overwrite its metadata,
            // genres or publication state.
            return;
        }
        jdbc.update("""
                insert into movie(id, slug, title, synopsis, release_year, runtime_seconds, maturity_rating,
                                  poster_url, backdrop_url, hls_manifest_url, featured, published)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                on conflict (id) do update set slug=excluded.slug, title=excluded.title, synopsis=excluded.synopsis,
                  release_year=excluded.release_year, runtime_seconds=excluded.runtime_seconds,
                  maturity_rating=excluded.maturity_rating, poster_url=excluded.poster_url,
                  backdrop_url=excluded.backdrop_url, hls_manifest_url=excluded.hls_manifest_url,
                  featured=excluded.featured, published=true, updated_at=now()
                """, movie.id(), movie.slug(), movie.title(), movie.synopsis(), movie.releaseYear(),
                movie.runtimeSeconds(), movie.maturityRating(), movie.posterUrl(), movie.backdropUrl(),
                movie.hlsManifestUrl(), movie.featured());
        jdbc.update("delete from movie_genre where movie_id=?", movie.id());
        for (String name : movie.genres()) {
            String slug = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            jdbc.update("insert into genre(slug, name) values (?, ?) on conflict (slug) do update set name=excluded.name",
                    slug, name);
            jdbc.update("""
                    insert into movie_genre(movie_id, genre_id)
                    select ?, id from genre where slug=? on conflict do nothing
                    """, movie.id(), slug);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record Manifest(String version, List<ManifestMovie> movies) {}
    record ManifestMovie(UUID id, String slug, String title, String synopsis, int releaseYear,
            int runtimeSeconds, String maturityRating, String posterUrl, String backdropUrl,
            String hlsManifestUrl, boolean featured, List<String> genres) {}
}
