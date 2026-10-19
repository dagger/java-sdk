package io.dagger.client;

import io.dagger.client.engineconn.Connection;
import io.dagger.client.telemetry.Telemetry;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParsingException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Answers one call of a module's Dang entrypoint: the request on standard input, the result in a
 * file, and a failure reported on standard error.
 *
 * <p>This class is in {@code io.dagger.client} because it writes a {@link JSON} scalar's raw value,
 * which only this package can read.
 */
public final class ModuleDispatcher {

  /** Starts the last line of standard error when the function fails; the entrypoint reads it. */
  public static final String FUNCTION_ERROR_MARKER = "::dagger-function-error::";

  /**
   * The engine hands the entrypoint the last 100 KiB of a failed exec's standard error, and JSON
   * encodes a character in at most six bytes.
   */
  static final int MAX_REPORTED_ERROR_LENGTH = 16 * 1024;

  private ModuleDispatcher() {}

  /** The generated entrypoint's dispatch switch. */
  @FunctionalInterface
  public interface Dispatch {
    JSON call(JSON parentJson, String parentName, String fnName, Map<String, JSON> inputArgs)
        throws Exception;
  }

  /**
   * Run the {@code engine-call} mode: {@code engine-call [--output PATH]}. Returns the process exit
   * code.
   */
  public static int engineCall(String[] args, Dispatch dispatch) {
    Connection.markModule();
    try (Telemetry telemetry = new Telemetry()) {
      Path output = outputPath(args);
      String result = dispatch(System.in, dispatch);
      if (output == null) {
        System.out.println(result);
      } else {
        if (output.getParent() != null) {
          Files.createDirectories(output.getParent());
        }
        Files.writeString(output, result, StandardCharsets.UTF_8);
      }
      return 0;
    } catch (Throwable e) {
      reportFailure(System.err, e);
      return 2;
    }
  }

  static String dispatch(InputStream in, Dispatch dispatch) throws Exception {
    JsonObject request = readRequest(in);
    String parentName = requiredString(request, "receiverType");
    String fnName = requiredString(request, "fnName");
    JSON parentJson = JSON.from(jsonText(request, "receiverValue"));
    JsonValue args = parse(jsonText(request, "fnArgs"), "fnArgs");
    if (args.getValueType() != JsonValue.ValueType.OBJECT) {
      throw new IllegalArgumentException("call request field fnArgs is not a JSON object");
    }
    Map<String, JSON> inputArgs = new HashMap<>();
    for (Map.Entry<String, JsonValue> arg : args.asJsonObject().entrySet()) {
      inputArgs.put(arg.getKey(), JSON.from(arg.getValue().toString()));
    }
    JSON value = dispatch.call(parentJson, parentName, fnName, inputArgs);
    return value == null ? "null" : value.convert();
  }

  /**
   * Report the function's error last, on one marker line holding its message as a JSON string. The
   * function's own output shares standard error, so the marker comes after everything else.
   */
  static void reportFailure(PrintStream err, Throwable e) {
    Throwable cause = e instanceof InvocationTargetException ite ? ite.getTargetException() : e;
    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
    cause.printStackTrace(err);
    err.println(FUNCTION_ERROR_MARKER + Json.createValue(truncate(message)));
    err.flush();
  }

  static String truncate(String message) {
    if (message.length() <= MAX_REPORTED_ERROR_LENGTH) {
      return message;
    }
    int end = MAX_REPORTED_ERROR_LENGTH;
    if (Character.isHighSurrogate(message.charAt(end - 1))) {
      end--;
    }
    return message.substring(0, end) + "...";
  }

  private static Path outputPath(String[] args) {
    Path output = null;
    for (int i = 1; i < args.length; i++) {
      if (args[i].equals("--output") && i + 1 < args.length) {
        output = Path.of(args[++i]);
      } else {
        throw new IllegalArgumentException(
            "usage: engine-call [--output PATH], got argument " + args[i]);
      }
    }
    return output;
  }

  private static JsonObject readRequest(InputStream in) {
    try (JsonParser parser = Json.createParser(in)) {
      if (!parser.hasNext() || parser.next() != JsonParser.Event.START_OBJECT) {
        throw new IllegalArgumentException("call request is not a JSON object");
      }
      JsonObject request = parser.getObject();
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
      return request;
    }
  }

  private static String requiredString(JsonObject request, String name) {
    JsonValue value = request.get(name);
    if (value == null || value.getValueType() != JsonValue.ValueType.STRING) {
      throw new IllegalArgumentException("call request is missing " + name);
    }
    return ((JsonString) value).getString();
  }

  /**
   * The JSON text of a request field. The entrypoint encodes a JSON scalar as a string holding JSON
   * text, which is decoded once; a JSON value is taken as it is. A missing field reads as null.
   */
  private static String jsonText(JsonObject request, String name) {
    JsonValue value = request.get(name);
    if (value == null) {
      return "null";
    }
    if (value.getValueType() != JsonValue.ValueType.STRING) {
      return value.toString();
    }
    String text = ((JsonString) value).getString();
    parse(text, name);
    return text;
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
}
