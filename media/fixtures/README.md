# Locally generated HLS fixtures

These three two-second clips are original test assets generated from FFmpeg's `color` and `sine` filters. They contain no third-party footage, music, trademarks, or copyrighted source material. Each fixture has an HLS media playlist and one MPEG-TS segment; colors and tone frequencies differ only to make the files independently identifiable.

The manifest runtime for each playable fixture is two seconds, matching its `#EXTINF` duration. Seed artwork URLs remain local development placeholders. Clients and the local media server should fall back to the repository-owned `media/artwork/poster-placeholder.svg` and `media/artwork/backdrop-placeholder.svg` when a title-specific artwork URL is unavailable.
