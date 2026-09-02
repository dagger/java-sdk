package io.dagger.client;

import io.dagger.client.exception.DaggerExecException;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParsingException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The manifest-v2 call protocol: one JSON request on standard input, one JSON result on standard
 * output.
 *
 * <p>This lives in {@code io.dagger.client} rather than {@code io.dagger.module} because it has to
 * write a {@link JSON} scalar's raw value, and {@code Scalar.convert()} is package-private here.
 * Serialising the scalar from another package would double-encode the result.
 */
public final class ModuleDispatcher {

  private ModuleDispatcher() {}

  /** The generated entrypoint's static dispatcher, as seen by this protocol. */
  @FunctionalInterface
  public interface Dispatch {
    JSON call(JSON parentJson, String parentName, String fnName, Map<String, JSON> inputArgs)
        throws Exception;
  }

  /**
   * Run one call and return a process exit code, reporting failures on standard error.
   *
   * <p>{@code call(...): JSON!} in the manifest-v2 interface has no error field, so a failure can
   * only be a non-zero exit. The envelope written here is not part of that contract and nothing
   * consumes it; it exists to show what an SDK would need a real error channel to carry.
   */
  public static int engineCallMain(Dispatch dispatch) {
    try {
      engineCall(System.in, System.out, dispatch);
      return 0;
    } catch (Exception e) {
      System.err.println(errorEnvelope(e));
      return 2;
    }
  }

  public static void engineCall(InputStream in, PrintStream out, Dispatch dispatch)
      throws Exception {
    JsonObject request;
    try (JsonParser parser = Json.createParser(in)) {
      if (!parser.hasNext() || parser.next() != JsonParser.Event.START_OBJECT) {
        throw new IllegalArgumentException("call request is not a JSON object");
      }
      request = parser.getObject();
      boolean trailing;
      try {
        trailing = parser.hasNext();
      } catch (JsonParsingException e) {
        // Parsson reports input after the request object by throwing rather than returning true.
        trailing = true;
      }
      if (trailing) {
        throw new IllegalArgumentException("call request has trailing data");
      }
    }

    String parentName = required(request, "receiverType");
    String fnName = required(request, "fnName");

    // receiverValue and fnArgs cross ModuleEntrypoint.call as JSON scalars, so the entrypoint's
    // JSON.encode writes them into the request as strings holding JSON text. Decode once.
    String receiverValue = nestedJson(request, "receiverValue", "{}");
    JSON parentJson = JSON.from(parseValue(receiverValue, "receiverValue"));

    Map<String, JSON> inputArgs = new HashMap<>();
    JsonObject args = parseObject(nestedJson(request, "fnArgs", null), "fnArgs");
    for (Map.Entry<String, JsonValue> arg : args.entrySet()) {
      inputArgs.put(arg.getKey(), JSON.from(arg.getValue().toString()));
    }

    // Module code shares this process's standard output, and the caller decodes that stream as a
    // single JSON value. Keep the module's own printing away from it.
    PrintStream saved = System.out;
    System.setOut(System.err);
    try {
      JSON value = dispatch.call(parentJson, parentName, fnName, inputArgs);
      out.println(value == null ? "null" : value.convert());
      out.flush();
    } finally {
      System.setOut(saved);
    }
  }

  private static String required(JsonObject request, String name) {
    JsonValue value = request.get(name);
    if (value == null || value.getValueType() != JsonValue.ValueType.STRING) {
      throw new IllegalArgumentException("call request is missing " + name);
    }
    return ((JsonString) value).getString();
  }

  /**
   * Read a field carrying JSON text as a JSON string. A null fallback makes the field required.
   */
  private static String nestedJson(JsonObject request, String name, String fallback) {
    JsonValue value = request.get(name);
    if (value == null || value.getValueType() == JsonValue.ValueType.NULL) {
      if (fallback == null) {
        throw new IllegalArgumentException("call request is missing " + name);
      }
      return fallback;
    }
    if (value.getValueType() != JsonValue.ValueType.STRING) {
      throw new IllegalArgumentException("call request field " + name + " is not JSON text");
    }
    return ((JsonString) value).getString();
  }

  private static JsonObject parseObject(String json, String name) {
    JsonValue value = parse(json, name);
    if (value.getValueType() != JsonValue.ValueType.OBJECT) {
      throw new IllegalArgumentException("call request field " + name + " is not a JSON object");
    }
    return value.asJsonObject();
  }

  /** Validate JSON text and hand back the original, so encodings survive untouched. */
  private static String parseValue(String json, String name) {
    parse(json, name);
    return json;
  }

  private static JsonValue parse(String json, String name) {
    try (JsonParser parser = Json.createParser(new StringReader(json))) {
      if (!parser.hasNext()) {
        throw new IllegalArgumentException("call request field " + name + " is empty");
      }
      parser.next();
      JsonValue value = parser.getValue();
      if (parser.hasNext()) {
        throw new IllegalArgumentException("call request field " + name + " has trailing data");
      }
      return value;
    } catch (JsonException e) {
      throw new IllegalArgumentException("call request field " + name + " is not valid JSON", e);
    }
  }

  private static String errorEnvelope(Exception e) {
    Throwable cause = e instanceof InvocationTargetException ite ? ite.getTargetException() : e;

    var builder = Json.createObjectBuilder();
    builder.add("message", String.valueOf(cause.getMessage()));
    builder.add("type", cause.getClass().getName());
    if (cause instanceof DaggerExecException exec) {
      // The exec accessors read GraphQL error extensions and throw when the engine did not send
      // them. This is the failure path; it must not fail again.
      put(builder, "stdout", () -> Json.createValue(String.valueOf(exec.getStdOut())));
      put(builder, "stderr", () -> Json.createValue(String.valueOf(exec.getStdErr())));
      put(builder, "cmd", () -> strings(exec.getCmd()));
      put(builder, "exitCode", () -> Json.createValue(exec.getExitCode()));
      put(builder, "path", () -> strings(exec.getPath()));
    }

    StringWriter out = new StringWriter();
    try (JsonGenerator generator = Json.createGenerator(out)) {
      generator.write(builder.build());
    }
    return out.toString();
  }

  private static void put(JsonObjectBuilder builder, String name, Supplier<JsonValue> value) {
    try {
      builder.add(name, value.get());
    } catch (RuntimeException ignored) {
      // the engine did not send this extension
    }
  }

  private static JsonValue strings(List<String> values) {
    var array = Json.createArrayBuilder();
    if (values != null) {
      values.forEach(array::add);
    }
    return array.build();
  }
}
