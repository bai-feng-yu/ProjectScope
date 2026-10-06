package agent_backend.tool;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ToolSchemaValidator {
    private final JsonMapper mapper;
    private final SchemaRegistry schemaRegistry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final ConcurrentMap<String, Schema> schemas = new ConcurrentHashMap<>();

    public ToolSchemaValidator(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public List<String> validate(Object inputSchema, Object arguments) {
        String schemaJson = mapper.writeValueAsString(inputSchema);
        Schema schema = schemas.computeIfAbsent(schemaJson,
                json -> schemaRegistry.getSchema(json, InputFormat.JSON));
        return schema.validate(mapper.writeValueAsString(arguments), InputFormat.JSON).stream()
                .map(error -> error.getMessage())
                .sorted()
                .toList();
    }
}
