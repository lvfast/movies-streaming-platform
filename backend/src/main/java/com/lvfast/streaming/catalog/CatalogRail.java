package com.lvfast.streaming.catalog;

import java.util.List;

public record CatalogRail(String key, String title, List<MovieSummary> items) {}
