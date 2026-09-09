package com.lvfast.streaming.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/catalog/home")
    CatalogHome home() {
        return catalog.home();
    }

    @GetMapping("/movies/{slug}")
    MovieDetails movie(@PathVariable String slug) {
        return catalog.movie(slug);
    }

    @GetMapping("/search")
    MoviePage search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalog.search(q, page, size);
    }
}
