package com.lvfast.streaming.administration;

import com.lvfast.streaming.media.catalog.MediaCatalog;
import com.lvfast.streaming.media.catalog.ReadyMediaAsset;
import com.lvfast.streaming.media.storage.MediaObjectStore;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Copies validated READY artwork output into the anonymous public artwork namespace. Nothing else is
 * ever readable anonymously: source uploads, unvalidated artwork and processed HLS stay behind the
 * media gateway. A repeated promotion of the same asset is a no-op, so a publish retried after a
 * response loss cannot produce a second object or a second promotion row.
 */
@Service
public class ArtworkPromotionService {

    private static final String DELIVERY_ROLE = "delivery";

    private final MediaCatalog catalog;
    private final MediaObjectStore store;
    private final JdbcTemplate jdbc;

    public ArtworkPromotionService(MediaCatalog catalog, MediaObjectStore store, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.store = store;
        this.jdbc = jdbc;
    }

    /**
     * Promotes one READY artwork kind for a movie and returns the promoted asset id. Missing artwork
     * is reported to the caller so publication can require the artwork it needs.
     */
    public UUID promote(UUID movieId, UUID assetId, String expectedKind) {
        ReadyMediaAsset asset = catalog.readyAsset(movieId, assetId)
                .orElseThrow(() -> new PublicationRejectedException(
                        "Artwork asset " + assetId + " is not a READY asset of this movie"));
        if (!asset.kind().equals(expectedKind)) {
            throw new PublicationRejectedException(
                    "Artwork asset " + assetId + " is a " + asset.kind() + ", not a " + expectedKind);
        }
        if (alreadyPromoted(asset.assetId(), asset.kind())) {
            return asset.assetId();
        }
        store.copy(DELIVERY_ROLE, asset.sourceKey(), asset.publicKey());
        record(asset);
        return asset.assetId();
    }

    public boolean isPromoted(UUID assetId, String kind) {
        return alreadyPromoted(assetId, kind);
    }

    private boolean alreadyPromoted(UUID assetId, String kind) {
        List<Long> rows = jdbc.queryForList("""
                select count(*) from media_publication where media_asset_id=? and asset_kind=?
                """, Long.class, assetId, kind);
        return !rows.isEmpty() && rows.getFirst() > 0;
    }

    private void record(ReadyMediaAsset asset) {
        try {
            jdbc.update("""
                    insert into media_publication(movie_id, media_asset_id, asset_kind, source_key, public_key)
                    values (?, ?, ?, ?, ?)
                    on conflict (media_asset_id, asset_kind) do nothing
                    """,
                    asset.movieId(), asset.assetId(), asset.kind(), asset.sourceKey(), asset.publicKey());
        } catch (DataIntegrityViolationException concurrentPromotion) {
            // A concurrent publisher promoted the same validated asset first; the object is identical.
        }
    }
}
