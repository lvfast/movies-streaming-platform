package com.lvfast.streaming.media.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/**
 * S3-compatible {@link MediaObjectStore}. Clients and presigners are built lazily per role from
 * {@link StorageProperties} so the application can start without storage when no upload is
 * performed, and so browser signing uses a distinct signing-only credential set.
 */
public class S3MediaObjectStore implements MediaObjectStore {

    private final StorageProperties properties;
    private final Map<String, S3Client> clients = new ConcurrentHashMap<>();
    private final Map<String, S3Presigner> presigners = new ConcurrentHashMap<>();

    public S3MediaObjectStore(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String initiate(String bucketRole, String key, String contentType, Map<String, String> metadata) {
        return run(bucketRole, "initiate multipart upload", client -> client
                .createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(bucket(bucketRole))
                        .key(key)
                        .contentType(contentType)
                        .metadata(metadata)
                        .build())
                .uploadId());
    }

    @Override
    public SignedPart signPart(String bucketRole, String key, String uploadId, int partNumber, Duration ttl) {
        try {
            PresignedUploadPartRequest presigned = presigner(bucketRole).presignUploadPart(
                    UploadPartPresignRequest.builder()
                            .uploadPartRequest(UploadPartRequest.builder()
                                    .bucket(bucket(bucketRole))
                                    .key(key)
                                    .uploadId(uploadId)
                                    .partNumber(partNumber)
                                    .build())
                            .signatureDuration(ttl)
                            .build());
            return new SignedPart(partNumber, presigned.url().toURI(), presigned.expiration(), Map.of());
        } catch (S3Exception | SdkClientException failure) {
            throw new StorageUnavailableException("presign upload part", failure);
        } catch (Exception failure) {
            throw new StorageUnavailableException("presign upload part", failure);
        }
    }

    @Override
    public PresignedGet presignGet(String bucketRole, String key, Duration ttl) {
        try {
            PresignedGetObjectRequest presigned = presigner(bucketRole).presignGetObject(
                    GetObjectPresignRequest.builder()
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(bucket(bucketRole))
                                    .key(key)
                                    .build())
                            .signatureDuration(ttl)
                            .build());
            return new PresignedGet(presigned.url().toURI(), presigned.expiration());
        } catch (S3Exception | SdkClientException failure) {
            throw new StorageUnavailableException("presign get object", failure);
        } catch (Exception failure) {
            throw new StorageUnavailableException("presign get object", failure);
        }
    }

    @Override
    public PartPage listParts(String bucketRole, String key, String uploadId, Integer marker) {
        return run(bucketRole, "list uploaded parts", client -> {
            ListPartsRequest.Builder request = ListPartsRequest.builder()
                    .bucket(bucket(bucketRole))
                    .key(key)
                    .uploadId(uploadId);
            if (marker != null) {
                request.partNumberMarker(marker);
            }
            ListPartsResponse response = client.listParts(request.build());
            List<StoredPart> items = response.parts().stream()
                    .map(part -> new StoredPart(part.partNumber(), part.eTag(), part.size()))
                    .toList();
            Integer next = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextPartNumberMarker()
                    : null;
            return new PartPage(items, next);
        });
    }

    @Override
    public void complete(String bucketRole, String key, String uploadId, List<CompletedPart> parts) {
        run(bucketRole, "complete multipart upload", client -> {
            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket(bucketRole))
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(parts.stream()
                                    .map(part -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                                            .partNumber(part.partNumber())
                                            .eTag(part.etag())
                                            .build())
                                    .toList())
                            .build())
                    .build());
            return null;
        });
    }

    @Override
    public ObjectHead head(String bucketRole, String key) {
        try {
            HeadObjectResponse response = client(bucketRole).headObject(HeadObjectRequest.builder()
                    .bucket(bucket(bucketRole))
                    .key(key)
                    .build());
            return new ObjectHead(response.contentLength(), response.eTag(), response.contentType(), response.metadata());
        } catch (S3Exception notFound) {
            if (notFound.statusCode() == 404) {
                return null;
            }
            throw new StorageUnavailableException("head object", notFound);
        } catch (SdkClientException failure) {
            throw new StorageUnavailableException("head object", failure);
        }
    }

    @Override
    public void abort(String bucketRole, String key, String uploadId) {
        try {
            client(bucketRole).abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket(bucketRole))
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (S3Exception missing) {
            if (missing.statusCode() == 404) {
                return; // Already aborted or completed; abort is idempotent.
            }
            throw new StorageUnavailableException("abort multipart upload", missing);
        } catch (SdkClientException failure) {
            throw new StorageUnavailableException("abort multipart upload", failure);
        }
    }

    @Override
    public InputStream read(String bucketRole, String key) {
        try {
            return client(bucketRole).getObject(GetObjectRequest.builder()
                    .bucket(bucket(bucketRole))
                    .key(key)
                    .build());
        } catch (S3Exception | SdkClientException failure) {
            throw new StorageUnavailableException("read object", failure);
        }
    }

    @Override
    public void put(String bucketRole, String key, Path file, String contentType) {
        run(bucketRole, "put object", client -> {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket(bucketRole))
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromFile(file));
            return null;
        });
    }

    @Override
    public void copy(String bucketRole, String fromKey, String toKey) {
        run(bucketRole, "copy object", client -> {
            client.copyObject(CopyObjectRequest.builder()
                    .bucket(bucket(bucketRole))
                    .sourceBucket(bucket(bucketRole))
                    .sourceKey(fromKey)
                    .destinationKey(toKey)
                    .build());
            return null;
        });
    }

    private String bucket(String bucketRole) {
        return properties.role(bucketRole).bucket();
    }

    private S3Client client(String bucketRole) {
        return clients.computeIfAbsent(bucketRole, this::buildClient);
    }

    private S3Presigner presigner(String bucketRole) {
        return presigners.computeIfAbsent(bucketRole, this::buildPresigner);
    }

    private S3Client buildClient(String bucketRole) {
        StorageProperties.Role role = properties.role(bucketRole);
        software.amazon.awssdk.services.s3.S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(role.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(role.accessKey(), role.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (role.hasEndpoint()) {
            builder.endpointOverride(URI.create(role.endpoint()));
        }
        return builder.build();
    }

    private S3Presigner buildPresigner(String bucketRole) {
        StorageProperties.Role role = properties.role(bucketRole);
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(role.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(role.accessKey(), role.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (role.hasEndpoint()) {
            builder.endpointOverride(URI.create(role.endpoint()));
        }
        return builder.build();
    }

    @FunctionalInterface
    private interface S3Operation<T> {
        T apply(S3Client client);
    }

    private <T> T run(String bucketRole, String operation, S3Operation<T> action) {
        try {
            return action.apply(client(bucketRole));
        } catch (S3Exception | SdkClientException failure) {
            throw new StorageUnavailableException(operation, failure);
        }
    }
}
