package com.lvfast.streaming.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Validates the checked-in media contract fixtures against their JSON Schemas (draft 2020-12), so a
 * change to a schema is exercised against its accepted and rejected examples. The validator uses
 * Jackson 2 (its own dependency), which is independent of the application's Jackson 3 runtime.
 */
class MediaContractSchemaTest {

    private static final Path CONTRACTS = Path.of("..", "contracts", "media");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @ParameterizedTest
    @CsvSource({
            "command-v1.schema.json, command-valid.json",
            "event-v1.schema.json, event-valid.json",
            "artifact-v1.schema.json, artifact-valid.json"
    })
    void acceptsValidFixtures(String schemaFile, String fixtureFile) throws Exception {
        JsonSchema schema = loadSchema(schemaFile);
        JsonNode fixture = loadFixture(fixtureFile);

        Set<ValidationMessage> errors = schema.validate(fixture);

        assertThat(errors).as("fixture %s must validate", fixtureFile).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "command-v1.schema.json, command-invalid.json",
            "event-v1.schema.json, event-invalid.json",
            "artifact-v1.schema.json, artifact-invalid.json"
    })
    void rejectsInvalidFixtures(String schemaFile, String fixtureFile) throws Exception {
        JsonSchema schema = loadSchema(schemaFile);
        JsonNode fixture = loadFixture(fixtureFile);

        Set<ValidationMessage> errors = schema.validate(fixture);

        assertThat(errors).as("fixture %s must be rejected", fixtureFile).isNotEmpty();
    }

    @Test
    void schemasAreDraft202012() throws Exception {
        for (String schema : new String[] {"command-v1.schema.json", "event-v1.schema.json",
                "artifact-v1.schema.json"}) {
            JsonNode node = read(CONTRACTS.resolve(schema));
            assertThat(node.get("$schema").asText())
                    .as("schema %s", schema)
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        }
    }

    private JsonSchema loadSchema(String file) throws Exception {
        return FACTORY.getSchema(read(CONTRACTS.resolve(file)));
    }

    private JsonNode loadFixture(String file) throws Exception {
        return read(CONTRACTS.resolve("fixtures").resolve(file));
    }

    private JsonNode read(Path path) throws Exception {
        return JSON.readTree(Files.readString(path));
    }
}
