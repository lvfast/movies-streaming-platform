package com.lvfast.streaming.media.upload;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Bounded batch of part numbers to presign; at most 32 unique in-range parts. */
public record PartSignRequest(
        @NotEmpty @Size(max = 32) List<@Positive Integer> partNumbers) {
}
