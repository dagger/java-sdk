package io.dagger.codegen;

import io.dagger.codegen.introspection.FormattedNames;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;
import jakarta.json.stream.JsonParsingException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Where codegen asks the engine to format names (see {@link FormattedNames}).
 *
 * <p>Codegen usually runs in a Maven exec the SDK module started, with the schema handed to it as a
 * file. Such an exec joins the engine's session as a nested client ({@code DAGGER_SESSION_PORT} and
 * {@code DAGGER_SESSION_TOKEN}), and a request without client metadata is served at the engine's
 * own API view, which has {@code Query.formatIdentifiers} from v1.0.0-beta.21: codegen asks there,
 * passing the generated schema's version, so the names follow that schema's dictionary. Outside a
 * session, it asks through the {@code dagger} CLI.
 */
public final class EngineQueries {

  static final String SESSION_PORT = "DAGGER_SESSION_PORT";
  static final String SESSION_TOKEN = "DAGGER_SESSION_TOKEN";

  private EngineQueries() {}

  /**
   * The runner for this environment: the nested session when there is one, else the dagger CLI
   * (resolved like the schema query: {@code dagger.bin}, {@code _EXPERIMENTAL_DAGGER_CLI_BIN}, else
   * {@code dagger}).
   */
  public static FormattedNames.QueryRunner fromEnvironment(Map<String, String> env, String bin) {
    FormattedNames.QueryRunner session = session(env);
    if (session != null) {
      return session;
    }
    String cli = DaggerCLIUtils.getBinary(bin);
    return (query, variables) ->
        DaggerCLIUtils.query(
            new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8)), cli, variables);
  }

  /** The nested session's runner, or null without a session in the environment. */
  static FormattedNames.QueryRunner session(Map<String, String> env) {
    String port = env.get(SESSION_PORT);
    String token = env.get(SESSION_TOKEN);
    if (port == null || port.isBlank() || token == null) {
      return null;
    }
    return session(URI.create("http://127.0.0.1:" + port.trim() + "/query"), token);
  }

  /**
   * A runner posting to a session's GraphQL endpoint, authenticated with its token. It sends no
   * client metadata, so the engine serves it at its own version.
   */
  static FormattedNames.QueryRunner session(URI endpoint, String token) {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    String auth =
        "Basic "
            + Base64.getEncoder().encodeToString((token + ":").getBytes(StandardCharsets.UTF_8));
    return (query, variables) -> {
      String body =
          Json.createObjectBuilder()
              .add("query", query)
              .add("variables", readObject(variables, "the query variables"))
              .build()
              .toString();
      HttpRequest request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json")
              .header("Authorization", auth)
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response;
      try {
        response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (ConnectException e) {
        throw new IOException("the Dagger session at " + endpoint + " is unreachable", e);
      }
      return data(endpoint, response.statusCode(), response.body());
    };
  }

  /** The data of a GraphQL response, or the failure its errors or status report. */
  static InputStream data(URI endpoint, int status, String body) throws IOException {
    JsonObject response;
    try {
      response = readObject(body, "the response");
    } catch (IOException e) {
      throw new IOException(
          "the Dagger session at " + endpoint + " answered HTTP " + status + ": " + body, e);
    }
    JsonValue errors = response.get("errors");
    if (errors != null
        && errors.getValueType() == ValueType.ARRAY
        && !errors.asJsonArray().isEmpty()) {
      StringBuilder messages = new StringBuilder();
      for (JsonValue error : errors.asJsonArray()) {
        String message =
            error.getValueType() == ValueType.OBJECT
                ? error.asJsonObject().getString("message", error.toString())
                : error.toString();
        messages.append(messages.length() == 0 ? "" : "; ").append(message);
      }
      throw new IOException(messages.toString());
    }
    JsonValue data = response.get("data");
    if (status / 100 != 2 || data == null || data.getValueType() != ValueType.OBJECT) {
      throw new IOException(
          "the Dagger session at " + endpoint + " answered HTTP " + status + ": " + body);
    }
    return new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static JsonObject readObject(String json, String what) throws IOException {
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      return reader.readObject();
    } catch (JsonParsingException | IllegalStateException e) {
      throw new IOException(what + " is not a JSON object", e);
    }
  }
}
