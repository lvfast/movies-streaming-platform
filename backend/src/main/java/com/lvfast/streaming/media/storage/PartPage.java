package com.lvfast.streaming.media.storage;

import java.util.List;

/** Cursor-paged part listing; {@code nextMarker} is {@code null} on the final page. */
public record PartPage(List<StoredPart> items, Integer nextMarker) {
}
