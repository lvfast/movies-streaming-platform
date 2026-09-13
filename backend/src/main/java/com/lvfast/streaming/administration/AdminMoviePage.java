package com.lvfast.streaming.administration;

import java.util.List;

public record AdminMoviePage(List<AdminMovieView> items, int page, int size, long total) {
}
