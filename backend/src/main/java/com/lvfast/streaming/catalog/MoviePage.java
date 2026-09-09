package com.lvfast.streaming.catalog;

import java.util.List;

public record MoviePage(List<MovieSummary> items, int page, int size, long total) {}
