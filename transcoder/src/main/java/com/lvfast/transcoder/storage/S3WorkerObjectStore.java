package com.lvfast.transcoder.storage;

import com.lvfast.transcoder.config.TranscoderProperties;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** S3-compatible {@link WorkerObjectStore} over the configured source and delivery buckets. */
public class S3WorkerObjectStore implements WorkerObjectStore {

    private final TranscoderProperties.Storage storage;
    private final Map<String, S3Client> clients = new ConcurrentHashMap<>();

    public S3WorkerObjectStore(TranscoderProperties.Storage storage) {
        this.storage = storage;
    }

    @Override
    public InputStream read(String role, String key) {
        try {
            return client(role).getObject(GetObjectRequest.builder().bucket(bucket(role)).key(key).build());
        } catch (S3Exception | SdkClientException failure) {
            throw new StorageUnavailableException("read object", failure);
        }
    }

    @Override
    public void put(String role, String key, Path file, String contentType) {
        try {
            client(role).putObject(
                    PutObjectRequest.builder().bucket(bucket(role)).key(key).contentType(contentType).build(),
                    RequestBody.fromFile(file));
        } catch (S3Exception | SdkClientException failure) {
            throw new StorageUnavailableException("put object", failure);
        }
    }

    @Override
    public Head head(String role, String key) {
        try {
            var response = client(role).headObject(HeadObjectRequest.builder().bucket(bucket(role)).key(key).build());
            return new Head(response.contentLength(), response.contentType());
        } catch (S3Exception missing) {
            if (missing.statusCode() == 404) {
                return null;
            }
            throw new StorageUnavailableException("head object", missing);
        } catch (SdkClientException failure) {
            throw new StorageUnavailableException("head object", failure);
        }
    }

    private TranscoderProperties.Role role(String name) {
        return switch (name) {
            case "source" -> storage.source();
            case "delivery" -> storage.delivery();
            default -> throw new IllegalArgumentException("Unknown storage role '" + name + "'");
        };
    }

    private String bucket(String name) {
        return role(name).bucket();
    }

    private S3Client client(String name) {
        return clients.computeIfAbsent(name, this::buildClient);
    }

    private S3Client buildClient(String name) {
        TranscoderProperties.Role role = role(name);
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(role.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(role.accessKey(), role.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (role.hasEndpoint()) {
            builder.endpointOverride(URI.create(role.endpoint()));
        }
        return builder.build();
    }
}
