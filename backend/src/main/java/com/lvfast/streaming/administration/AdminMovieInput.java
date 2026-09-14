package com.lvfast.streaming.administration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Editor-submitted movie fields. Runtime and media URLs are server-owned and never accepted here. */
public record AdminMovieInput(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 120) String slug,
        @Size(max = 10000) String synopsis,
        @Min(1888) @Max(2200) int releaseYear,
        @NotBlank String maturityRating,
        List<@Positive Integer> genreIds,
        boolean featured) {
}
