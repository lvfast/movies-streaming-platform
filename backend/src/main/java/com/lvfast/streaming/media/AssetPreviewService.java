package com.lvfast.streaming.media;

import com.lvfast.streaming.media.catalog.MediaCatalog;
import com.lvfast.streaming.media.catalog.ReadyMediaAsset;
import com.lvfast.streaming.media.storage.MediaObjectStore;
import com.lvfast.streaming.media.storage.PresignedGet;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues a 60-second private read URL for a validated READY artwork asset. Only the server can see
 * the signed URL, and only after the shared admin authorization has passed.
 */
@Service
public class AssetPreviewService {

    private static final Duration PREVIEW_TTL = Duration.ofSeconds(60);
    private static final String DELIVERY_ROLE = "delivery";

    private final MediaCatalog catalog;
    private final MediaObjectStore store;

    public AssetPreviewService(MediaCatalog catalog, MediaObjectStore store) {
        this.catalog = catalog;
        this.store = store;
    }

    public AssetPreview preview(UUID assetId) {
        ReadyMediaAsset asset = catalog.readyAssetById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));
        PresignedGet signed = store.presignGet(DELIVERY_ROLE, asset.sourceKey(), PREVIEW_TTL);
        return new AssetPreview(signed.url().toString(), signed.expiresAt().toString());
    }
}
