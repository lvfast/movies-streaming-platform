package com.lvfast.transcoder.jobs;

import com.lvfast.transcoder.config.TranscoderProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Private HTTP client for the backend claim and heartbeat endpoints using machine authentication. */
@Component
public class BackendJobClient {

    private final String baseUrl;
    private final String credential;
    private final String workerId;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public BackendJobClient(TranscoderProperties properties, HttpClient http) {
        this.baseUrl = trimTrailingSlash(properties.backendBaseUrl());
        this.credential = properties.credential();
        this.workerId = properties.workerId();
        this.http = http;
    }

    public ClaimResult claim(UUID jobId) {
        JsonNode body = post("/internal/v1/jobs/" + jobId + "/claim", Map.of("workerId", workerId));
        return new ClaimResult(
                jobId.toString(),
                text(body, "disposition"),
                text(body, "reason"),
                text(body, "attemptId"),
                text(body, "leaseUntil"),
                text(body, "kind"),
                text(body, "source"),
                text(body, "outputPrefix"),
                text(body, "movieId"),
                text(body, "mediaVersionId"),
                text(body, "assetId"),
                text(body, "profile"));
    }

    public String heartbeat(UUID jobId, String attemptId) {
        JsonNode body = post("/internal/v1/jobs/" + jobId + "/heartbeat",
                Map.of("workerId", workerId, "attemptId", attemptId));
        return text(body, "leaseUntil");
    }

    private JsonNode post(String path, Map<String, String> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                throw new LeaseLostException("Backend rejected heartbeat/claim with LEASE_LOST");
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Backend returned HTTP " + response.statusCode() + " for " + path);
            }
            return json.readTree(response.body());
        } catch (LeaseLostException expected) {
            throw expected;
        } catch (Exception failure) {
            throw new IllegalStateException("Backend request failed for " + path, failure);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
