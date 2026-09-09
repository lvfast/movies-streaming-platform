package com.lvfast.streaming.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "movie")
public class Movie {
    @Id private UUID id;
    @Column(nullable = false) private String slug;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String synopsis;
    @Column(name = "release_year", nullable = false) private short releaseYear;
    @Column(name = "runtime_seconds", nullable = false) private int runtimeSeconds;
    @Column(name = "maturity_rating", nullable = false) private String maturityRating;
    @Column(name = "poster_url", nullable = false) private String posterUrl;
    @Column(name = "backdrop_url", nullable = false) private String backdropUrl;
    @Column(name = "hls_manifest_url") private String hlsManifestUrl;
    @Column(nullable = false) private boolean featured;
    @Column(nullable = false) private boolean published;

    @ManyToMany
    @JoinTable(name = "movie_genre", joinColumns = @JoinColumn(name = "movie_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new LinkedHashSet<>();

    protected Movie() {}
}
