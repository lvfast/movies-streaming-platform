package com.lvfast.streaming.library;

import com.lvfast.streaming.catalog.MoviePage;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/watchlist")
public class LibraryController {
    private final LibraryService library;

    LibraryController(LibraryService library) {
        this.library = library;
    }

    @GetMapping
    MoviePage watchlist(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return library.watchlist(userId(jwt), page, size);
    }

    @PutMapping("/{movieId}")
    ResponseEntity<Void> add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId) {
        library.add(userId(jwt), movieId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{movieId}")
    ResponseEntity<Void> remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID movieId) {
        library.remove(userId(jwt), movieId);
        return ResponseEntity.noContent().build();
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
