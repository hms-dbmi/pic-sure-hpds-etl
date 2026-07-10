package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.DataException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads JSON inputs into domain types, converting parse failures into
 * {@link DataException} so a malformed file yields a DATA_ERROR exit rather than an
 * opaque stack trace.
 */
@Component
public class JsonReader {

    private final ObjectMapper mapper;

    public JsonReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Reads a single JSON object/value into {@code type}. */
    public <T> T read(InputStream in, Class<T> type) {
        try {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new DataException("Failed to parse JSON as " + type.getSimpleName(), e);
        }
    }

    /** Reads a JSON array into a {@code List<T>}. */
    public <T> List<T> readList(InputStream in, Class<T> elementType) {
        try {
            return mapper.readValue(in,
                    mapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (IOException e) {
            throw new DataException("Failed to parse JSON array of " + elementType.getSimpleName(), e);
        }
    }
}
