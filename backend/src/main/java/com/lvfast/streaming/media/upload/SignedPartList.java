package com.lvfast.streaming.media.upload;

import com.lvfast.streaming.media.storage.SignedPart;
import java.util.List;

/** Presigned part URLs for a bounded upload batch. */
public record SignedPartList(List<SignedPart> items) {
}
