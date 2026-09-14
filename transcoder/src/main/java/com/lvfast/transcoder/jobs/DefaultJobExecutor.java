package com.lvfast.transcoder.jobs;

import com.lvfast.transcoder.artwork.ArtworkProcessor;
import com.lvfast.transcoder.common.ProcessingFailure;
import com.lvfast.transcoder.config.TranscoderProperties;
import com.lvfast.transcoder.encode.HlsEncoder;
import com.lvfast.transcoder.events.MediaEventPublisher;
import com.lvfast.transcoder.output.Artifact;
import com.lvfast.transcoder.output.ArtifactObject;
import com.lvfast.transcoder.output.ArtifactWriter;
import com.lvfast.transcoder.process.ProcessTimeoutException;
import com.lvfast.transcoder.probe.Dimensions;
import com.lvfast.transcoder.probe.ProbedVideo;
import com.lvfast.transcoder.probe.VideoDimensions;
import com.lvfast.transcoder.probe.VideoProbe;
import com.lvfast.transcoder.storage.StorageUnavailableException;
import com.lvfast.transcoder.storage.WorkerObjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Happy-path job pipeline: download, probe, encode or normalize, upload immutable objects, write the
 * manifest last and publish the completed result. Processing failures publish a failed event;
 * lease loss is rethrown so the command can be retried after the attempt is abandoned.
 */
@Component
public class DefaultJobExecutor implements JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobExecutor.class);

    private static final String SOURCE_ROLE = "source";
    private static final String DELIVERY_ROLE = "delivery";
    private static final String ARTIFACT_KEY = "artifact.json";

    private final WorkerObjectStore store;
    private final VideoProbe probe;
    private final HlsEncoder encoder;
    private final ArtworkProcessor artwork;
    private final ArtifactWriter writer;
    private final MediaEventPublisher publisher;
    private final Path workDirRoot;

    public DefaultJobExecutor(
            WorkerObjectStore store,
            VideoProbe probe,
            HlsEncoder encoder,
            ArtworkProcessor artwork,
            ArtifactWriter writer,
            MediaEventPublisher publisher,
            TranscoderProperties properties) {
        this.store = store;
        this.probe = probe;
        this.encoder = encoder;
        this.artwork = artwork;
        this.writer = writer;
        this.publisher = publisher;
        this.workDirRoot = properties.workDir();
    }

    @Override
    public void execute(ClaimResult claim, LeaseGuard lease) {
        long[] sequence = {0};
        Path workDir = createWorkDir();
        try {
            if ("TRANSCODE".equals(claim.kind())) {
                transcode(claim, lease, workDir, sequence);
            } else {
                processArtwork(claim, lease, workDir, sequence);
            }
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (ProcessingFailure failure) {
            log.warn("Job {} failed: {} - {}", claim.jobId(), failure.code(), failure.getMessage());
            publisher.failed(claim, next(sequence), failure.code(), failure.getMessage());
        } catch (StorageUnavailableException storage) {
            log.warn("Job {} storage unavailable", claim.jobId(), storage);
            publisher.failed(claim, next(sequence), "STORAGE_UNAVAILABLE", "Storage unavailable");
        } catch (ProcessTimeoutException timeout) {
            log.warn("Job {} timed out", claim.jobId());
            publisher.failed(claim, next(sequence), "PROCESS_TIMEOUT", "Processing timed out");
        } catch (Exception unexpected) {
            log.error("Unexpected worker failure for job " + claim.jobId(), unexpected);
            publisher.failed(claim, next(sequence), "INTERNAL_ERROR",
                    "Unexpected worker failure: " + unexpected);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void transcode(ClaimResult claim, LeaseGuard lease, Path workDir, long[] sequence) {
        publisher.progress(claim, next(sequence), "DOWNLOADING", 5);
        Path source = download(claim, workDir);
        lease.assertActive();

        publisher.progress(claim, next(sequence), "PROBING", 20);
        ProbedVideo video = probe.probe(source);
        Dimensions target = VideoDimensions.fit(video.dimensions().width(), video.dimensions().height());
        lease.assertActive();

        publisher.progress(claim, next(sequence), "ENCODING", 40);
        Path output = createOutputDir(workDir);
        encoder.encode(source, output, target);
        lease.assertActive();

        publisher.progress(claim, next(sequence), "UPLOADING", 70);
        List<Path> files = listOutputFiles(output);
        List<ArtifactObject> objects = upload(claim.outputPrefix(), files);
        Artifact manifest = new Artifact(
                1, claim.jobId(), claim.attemptId(), "TRANSCODE", claim.profile(), claim.movieId(),
                claim.mediaVersionId(), null, target.width(), target.height(), video.durationSeconds(),
                "h264", "aac", "index.m3u8", objects);
        writeManifest(claim, manifest);

        publisher.progress(claim, next(sequence), "VALIDATING", 95);
        lease.assertActive();
        publisher.completed(claim, next(sequence), ARTIFACT_KEY);
    }

    private void processArtwork(ClaimResult claim, LeaseGuard lease, Path workDir, long[] sequence) {
        publisher.progress(claim, next(sequence), "DOWNLOADING", 5);
        Path source = download(claim, workDir);
        lease.assertActive();

        publisher.progress(claim, next(sequence), "ENCODING", 40);
        Dimensions target = artworkTarget(claim.profile());
        Path output = createOutputDir(workDir);
        artwork.process(source, output, target);
        lease.assertActive();

        publisher.progress(claim, next(sequence), "UPLOADING", 70);
        Path image = output.resolve("image.jpg");
        List<ArtifactObject> objects = upload(claim.outputPrefix(), List.of(image));
        Artifact manifest = new Artifact(
                1, claim.jobId(), claim.attemptId(), "ARTWORK", claim.profile(), claim.movieId(),
                null, claim.assetId(), target.width(), target.height(), null, null, null, null, objects);
        writeManifest(claim, manifest);

        publisher.progress(claim, next(sequence), "VALIDATING", 95);
        lease.assertActive();
        publisher.completed(claim, next(sequence), ARTIFACT_KEY);
    }

    private Path download(ClaimResult claim, Path workDir) {
        Path source = workDir.resolve("source");
        try {
            store.download(SOURCE_ROLE, claim.source(), source);
        } catch (IOException failure) {
            throw new ProcessingFailure("SOURCE_INVALID", "Source could not be downloaded");
        }
        WorkerObjectStore.Head head = store.head(SOURCE_ROLE, claim.source());
        long size;
        try {
            size = Files.size(source);
        } catch (IOException failure) {
            throw new ProcessingFailure("SOURCE_INVALID", "Downloaded source is unreadable");
        }
        if (head == null || head.sizeBytes() != size) {
            throw new ProcessingFailure("SOURCE_INVALID", "Downloaded bytes do not match the stored source");
        }
        return source;
    }

    private List<ArtifactObject> upload(String prefix, List<Path> files) {
        List<ArtifactObject> objects = new ArrayList<>();
        for (Path file : files) {
            String key = file.getFileName().toString();
            try {
                store.put(DELIVERY_ROLE, prefix + key, file, contentType(key));
            } catch (StorageUnavailableException storage) {
                throw storage;
            }
            long size;
            try {
                size = Files.size(file);
            } catch (IOException failure) {
                throw new ProcessingFailure("OUTPUT_INVALID", "Output object is unreadable");
            }
            objects.add(new ArtifactObject(key, size, ArtifactWriter.sha256Hex(file)));
        }
        return objects;
    }

    private void writeManifest(ClaimResult claim, Artifact manifest) {
        try {
            Path manifestFile = Files.createTempFile(workDirRoot == null ? null : workDirRoot, "artifact-", ".json");
            writer.write(manifestFile, manifest);
            try {
                store.put(DELIVERY_ROLE, claim.outputPrefix() + ARTIFACT_KEY, manifestFile, "application/json");
            } finally {
                Files.deleteIfExists(manifestFile);
            }
        } catch (StorageUnavailableException storage) {
            throw storage;
        } catch (IOException failure) {
            throw new ProcessingFailure("OUTPUT_INVALID", "Artifact manifest could not be written");
        }
    }

    private Dimensions artworkTarget(String profile) {
        return switch (profile) {
            case "poster-600x900" -> new Dimensions(600, 900);
            case "backdrop-1600x900" -> new Dimensions(1600, 900);
            default -> throw new ProcessingFailure("OUTPUT_INVALID", "Unknown artwork profile '" + profile + "'");
        };
    }

    private List<Path> listOutputFiles(Path output) {
        try {
            List<Path> files = new ArrayList<>();
            files.add(output.resolve("index.m3u8"));
            try (var stream = Files.list(output)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".ts"))
                        .sorted(Comparator.comparing(Path::getFileName))
                        .forEach(files::add);
            }
            return files;
        } catch (IOException failure) {
            throw new ProcessingFailure("OUTPUT_INVALID", "Encoded output could not be listed");
        }
    }

    private String contentType(String key) {
        if (key.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (key.endsWith(".ts")) {
            return "video/mp2t";
        }
        if (key.endsWith(".jpg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private Path createWorkDir() {
        try {
            if (workDirRoot != null) {
                Files.createDirectories(workDirRoot);
            }
            return Files.createTempDirectory(workDirRoot == null ? null : workDirRoot, "job-");
        } catch (IOException failure) {
            throw new ProcessingFailure("DISK_EXHAUSTED", "Unable to create a working directory");
        }
    }

    private Path createOutputDir(Path workDir) {
        try {
            Path output = workDir.resolve("output");
            Files.createDirectories(output);
            return output;
        } catch (IOException failure) {
            throw new ProcessingFailure("DISK_EXHAUSTED", "Unable to create the output directory");
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private long next(long[] sequence) {
        return ++sequence[0];
    }
}
