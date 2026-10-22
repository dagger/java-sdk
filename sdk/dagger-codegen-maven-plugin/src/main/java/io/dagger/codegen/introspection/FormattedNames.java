package io.dagger.codegen.introspection;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;
import jakarta.json.stream.JsonParsingException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Schema names formatted by the engine, for Java identifiers in Java's own conventions: members,
 * parameters, setters and accessors in camelCase with acronyms written like words ({@code asJson},
 * {@code withGpu}), classes in PascalCase with acronyms written like words ({@code JsonValue},
 * {@code Llm}), and enum constants in SCREAMING_SNAKE_CASE ({@code PER_SESSION}).
 *
 * <p>Codegen doesn't parse names itself: the naming rules live in the engine, which formats them
 * with {@code Query.formatIdentifiers}. Its {@code version} argument is the schema's own {@code
 * __schemaVersion}, so the names follow the dictionary of the schema being generated whatever the
 * view of the session that formats them.
 *
 * <p>The gate is the schema being generated: without {@code Query.formatIdentifiers} (views before
 * v1.0.0-beta.21), nothing is formatted, and codegen keeps the schema's names, generating the same
 * code as before. With it, the names have to be formatted: a failure fails codegen rather than
 * falling back to the schema's names, which would generate a different API.
 */
public final class FormattedNames {

  /** The key of the member names (methods, parameters, accessors) in a names file. */
  public static final String MEMBERS = "CAMEL:CAPITALIZED";

  /** The key of the class names in a names file. */
  public static final String TYPES = "PASCAL:CAPITALIZED";

  /** The key of the enum constant names in a names file. */
  public static final String ENUM_VALUES = "SCREAMING_SNAKE:UPPERCASE";

  /** The formats Java codegen uses, keyed like a names file: {@code CASING:ACRONYMS}. */
  public static final List<String> FORMATS = List.of(MEMBERS, TYPES, ENUM_VALUES);

  /**
   * Bounds the size of the names sent per request. The names may go to {@code dagger query} as a
   * command-line argument, which Linux caps at 128 KiB; the core schema's names fit in one.
   */
  static final int BATCH_BYTES = 64 << 10;

  /** Formats a batch of names in every format of {@link #FORMATS}, one alias per format. */
  public static final String QUERY = query();

  private static final Pattern HAS_ALNUM = Pattern.compile("[A-Za-z0-9]");

  private FormattedNames() {}

  private static String query() {
    StringBuilder query =
        new StringBuilder("query FormatIdentifiers($names: [String!]!, $version: String) {");
    for (int i = 0; i < FORMATS.size(); i++) {
      String[] format = FORMATS.get(i).split(":");
      query
          .append(" f")
          .append(i)
          .append(": formatIdentifiers(names: $names, casing: ")
          .append(format[0])
          .append(", acronyms: ")
          .append(format[1])
          .append(", version: $version)");
    }
    return query.append(" }").toString();
  }

  /**
   * Runs a GraphQL query with variables (a JSON object) against the engine and returns the
   * response's data as JSON.
   */
  @FunctionalInterface
  public interface QueryRunner {
    InputStream query(String query, String variables) throws IOException, InterruptedException;
  }

  /** Whether a schema has {@code Query.formatIdentifiers}: the gate for formatted names. */
  public static boolean hasFormatIdentifiers(byte[] schemaJson) throws IOException {
    return hasFormatIdentifiers(read(schemaJson, "the introspection JSON"));
  }

  /**
   * Formats the names of a schema through the engine, in batches, in every format Java codegen
   * uses, passing the schema's {@code __schemaVersion} as the dictionary version. Returns the names
   * keyed by format ({@link #MEMBERS}, {@link #TYPES}, {@link #ENUM_VALUES}), like a names file, or
   * null, formatting nothing and never calling the runner, when the schema has no {@code
   * Query.formatIdentifiers}.
   *
   * @throws IOException when the names can't be formatted, with a message that says why codegen
   *     needs them
   */
  public static Map<String, Map<String, String>> fetch(byte[] schemaJson, QueryRunner runner)
      throws IOException, InterruptedException {
    JsonObject root = read(schemaJson, "the introspection JSON");
    if (!hasFormatIdentifiers(root)) {
      return null;
    }
    String version = schemaVersion(root);
    Map<String, Map<String, String>> formatted = new HashMap<>();
    for (String format : FORMATS) {
      formatted.put(format, new HashMap<>());
    }
    for (List<String> batch : batches(names(root), BATCH_BYTES)) {
      var variables = Json.createObjectBuilder().add("names", Json.createArrayBuilder(batch));
      if (version == null) {
        variables.addNull("version");
      } else {
        variables.add("version", version);
      }
      JsonObject data;
      try (InputStream in = runner.query(QUERY, variables.build().toString())) {
        data = read(in.readAllBytes(), "the formatIdentifiers response");
      } catch (IOException e) {
        throw failure(version, e.getMessage(), e);
      }
      for (int i = 0; i < FORMATS.size(); i++) {
        JsonValue result = data.get("f" + i);
        if (result == null || result.getValueType() != ValueType.ARRAY) {
          throw failure(version, "the response has no formatIdentifiers result: " + data, null);
        }
        JsonArray out = result.asJsonArray();
        if (out.size() != batch.size()) {
          throw failure(
              version, "sent " + batch.size() + " names, got " + out.size() + " back", null);
        }
        Map<String, String> names = formatted.get(FORMATS.get(i));
        for (int j = 0; j < batch.size(); j++) {
          names.put(batch.get(j), out.getString(j));
        }
      }
    }
    return formatted;
  }

  private static IOException failure(String version, String why, Throwable cause) {
    return new IOException(
        "the schema has Query.formatIdentifiers (schema version "
            + (version == null ? "unknown" : version)
            + "), so its Java names follow the engine's naming rules, but the engine could not"
            + " format them: "
            + why,
        cause);
  }

  /**
   * Reads the names Java codegen uses from a names file, as {@code codegen introspect --names-out}
   * writes it: {@code {"CAMEL:CAPITALIZED": {"withGPU": "withGpu", ...}, "PASCAL:CAPITALIZED":
   * {...}, ...}}, keyed by format. Formats Java codegen doesn't use are left out, and a format the
   * file doesn't have keeps the schema's names. Returns null when the file has none of them.
   */
  public static Map<String, Map<String, String>> read(InputStream namesJson) throws IOException {
    JsonObject root = read(namesJson.readAllBytes(), "the names file");
    Map<String, Map<String, String>> formatted = new HashMap<>();
    for (String format : FORMATS) {
      JsonValue names = root.get(format);
      if (names == null || names.getValueType() != ValueType.OBJECT) {
        continue;
      }
      Map<String, String> byName = new HashMap<>();
      for (Map.Entry<String, JsonValue> e : names.asJsonObject().entrySet()) {
        if (e.getValue() instanceof JsonString s) {
          byName.put(e.getKey(), s.getString());
        }
      }
      formatted.put(format, byName);
    }
    return formatted.isEmpty() ? null : formatted;
  }

  static boolean hasFormatIdentifiers(JsonObject root) {
    JsonObject schema = object(root, "__schema");
    if (schema == null) {
      return false;
    }
    JsonObject queryType = object(schema, "queryType");
    String queryName = queryType == null ? "Query" : queryType.getString("name", "Query");
    for (JsonObject type : objects(schema, "types")) {
      if (queryName.equals(type.getString("name", null))) {
        return objects(type, "fields").stream()
            .anyMatch(field -> "formatIdentifiers".equals(field.getString("name", null)));
      }
    }
    return false;
  }

  /** The schema's {@code __schemaVersion}, or null when it has none. */
  static String schemaVersion(JsonObject root) {
    JsonValue version = root.get("__schemaVersion");
    if (version instanceof JsonString s && !s.getString().isEmpty()) {
      return s.getString();
    }
    return null;
  }

  /**
   * The distinct type, field, argument, input field and enum value names in the schema, sorted,
   * leaving out introspection names ("__" prefix) and names the engine can't format (non-ASCII, or
   * with no letters or digits).
   */
  static List<String> names(JsonObject root) {
    TreeSet<String> names = new TreeSet<>();
    JsonObject schema = object(root, "__schema");
    if (schema == null) {
      return List.of();
    }
    for (JsonObject type : objects(schema, "types")) {
      String typeName = type.getString("name", "");
      if (typeName.startsWith("__")) {
        continue;
      }
      addName(names, typeName);
      for (JsonObject field : objects(type, "fields")) {
        addName(names, field.getString("name", ""));
        for (JsonObject arg : objects(field, "args")) {
          addName(names, arg.getString("name", ""));
        }
      }
      for (JsonObject inputField : objects(type, "inputFields")) {
        addName(names, inputField.getString("name", ""));
      }
      for (JsonObject enumValue : objects(type, "enumValues")) {
        addName(names, enumValue.getString("name", ""));
      }
    }
    return new ArrayList<>(names);
  }

  /**
   * Splits names into batches of at most maxBytes as a JSON array (but at least one name each).
   * Schema names need no escaping, so each takes its length plus quotes and a comma.
   */
  static List<List<String>> batches(List<String> names, int maxBytes) {
    List<List<String>> batches = new ArrayList<>();
    int start = 0;
    while (start < names.size()) {
      int end = start;
      int size = 2;
      while (end < names.size()
          && (end == start || size + names.get(end).length() + 3 <= maxBytes)) {
        size += names.get(end).length() + 3;
        end++;
      }
      batches.add(names.subList(start, end));
      start = end;
    }
    return batches;
  }

  private static void addName(TreeSet<String> names, String name) {
    if (name.startsWith("__")
        || !name.chars().allMatch(c -> c < 0x80)
        || !HAS_ALNUM.matcher(name).find()) {
      return;
    }
    names.add(name);
  }

  private static JsonObject object(JsonObject parent, String key) {
    JsonValue value = parent.get(key);
    return value != null && value.getValueType() == ValueType.OBJECT ? value.asJsonObject() : null;
  }

  private static List<JsonObject> objects(JsonObject parent, String key) {
    JsonValue value = parent.get(key);
    if (value == null || value.getValueType() != ValueType.ARRAY) {
      return List.of();
    }
    return value.asJsonArray().stream()
        .filter(v -> v.getValueType() == ValueType.OBJECT)
        .map(JsonValue::asJsonObject)
        .toList();
  }

  private static JsonObject read(byte[] json, String what) throws IOException {
    try (JsonReader reader = Json.createReader(new ByteArrayInputStream(json))) {
      return reader.readObject();
    } catch (JsonParsingException | IllegalStateException e) {
      throw new IOException(what + " is not a JSON object: " + e.getMessage(), e);
    }
  }
}
