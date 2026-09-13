package com.lvfast.streaming.media;

import java.util.List;

/** Zero-based paged collection response ({@code items,page,size,total}). */
public record MediaPage<T>(List<T> items, int page, int size, long total) {
}
