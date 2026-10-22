package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dagger.client.engineconn.TestConnections;
import io.dagger.client.modules.core.JSON;
import io.dagger.client.modules.core.JsonConverter;
import jakarta.json.Json;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleDispatcherTest {

  @Test
  void theRequestReachesTheSwitchVerbatim() throws Exception {
    var seen =
        new Object() {
          Map<String, JSON> args;
          String parentName;
          String fnName;
          JSON parentJson;
        };

    String result =
        dispatch(
            """
            {"receiverType":"Hello","receiverValue":"{\\"prefix\\":\\"Hi\\"}",
             "fnName":"greet","fnArgs":"{\\"name\\":\\"World\\",\\"times\\":3}"}
            """,
            (parentJson, parentName, fnName, inputArgs) -> {
              seen.parentJson = parentJson;
              seen.parentName = parentName;
              seen.fnName = fnName;
              seen.args = inputArgs;
              return JSON.from("\"Hi, World\"");
            });

    assertThat(seen.parentName).isEqualTo("Hello");
    assertThat(seen.fnName).isEqualTo("greet");
    assertThat(seen.parentJson.convert()).isEqualTo("{\"prefix\":\"Hi\"}");
    assertThat(seen.args.get("name").convert()).isEqualTo("\"World\"");
    assertThat(seen.args.get("times").convert()).isEqualTo("3");
    assertThat(result).isEqualTo("\"Hi, World\"");
  }

  /** The specification embeds the values as JSON; Dang encodes a JSON scalar as JSON text. */
  @Test
  void valuesAreAcceptedAsJsonAndAsJsonText() throws Exception {
    var seen = new String[2];

    dispatch(
        "{\"receiverType\":\"Hello\",\"receiverValue\":{\"a\":1},\"fnName\":\"f\","
            + "\"fnArgs\":{\"name\":\"World\"}}",
        (parentJson, parentName, fnName, inputArgs) -> {
          seen[0] = parentJson.convert();
          seen[1] = inputArgs.get("name").convert();
          return null;
        });

    assertThat(seen[0]).isEqualTo("{\"a\":1}");
    assertThat(seen[1]).isEqualTo("\"World\"");
  }

  @Test
  void aConstructorCallCarriesItsTypeAndAnEmptyFunctionName() throws Exception {
    var seen = new String[3];

    dispatch(
        "{\"receiverType\":\"Hello\",\"receiverValue\":\"null\",\"fnName\":\"\",\"fnArgs\":\"{}\"}",
        (parentJson, parentName, fnName, inputArgs) -> {
          seen[0] = parentName;
          seen[1] = fnName;
          seen[2] = parentJson.convert();
          return JSON.from("{}");
        });

    assertThat(seen).containsExactly("Hello", "", "null");
  }

  @Test
  void aVoidResultIsJsonNull() throws Exception {
    assertThat(dispatch(request("{}"), (parentJson, parentName, fnName, inputArgs) -> null))
        .isEqualTo("null");
  }

  @Test
  void malformedRequestsAreRejected() {
    ModuleDispatcher.Dispatch never =
        (parentJson, parentName, fnName, inputArgs) -> JSON.from("null");

    assertThatThrownBy(() -> dispatch("{\"fnName\":\"greet\",\"fnArgs\":\"{}\"}", never))
        .hasMessageContaining("receiverType");
    assertThatThrownBy(() -> dispatch("{\"receiverType\":\"Hello\",\"fnArgs\":\"{}\"}", never))
        .hasMessageContaining("fnName");
    assertThatThrownBy(() -> dispatch(request("{}") + "{}", never))
        .hasMessageContaining("trailing data");
    assertThatThrownBy(() -> dispatch(request("{}{}"), never)).hasMessageContaining("fnArgs");
    assertThatThrownBy(() -> dispatch(request("[]"), never)).hasMessageContaining("fnArgs");
    assertThatThrownBy(() -> dispatch("{\"receiverType\":\"Hello\",\"fnName\":\"greet\"}", never))
        .hasMessageContaining("fnArgs");
  }

  @Test
  void theResultIsWrittenToTheOutputFile(@TempDir Path dir) throws Exception {
    Path output = dir.resolve("dagger/result.json");

    int code =
        withStdin(
            request("{}"),
            () ->
                ModuleDispatcher.engineCall(
                    new String[] {"engine-call", "--output", output.toString()},
                    (parentJson, parentName, fnName, inputArgs) -> {
                      System.out.println("a log line of the module");
                      return JSON.from("[\"a\",\"b\"]");
                    }));

    assertThat(code).isZero();
    assertThat(Files.readString(output)).isEqualTo("[\"a\",\"b\"]");
  }

  @Test
  void anEngineCallMarksTheProcessAsAModule(@TempDir Path dir) throws Exception {
    withStdin(
        request("{}"),
        () ->
            ModuleDispatcher.engineCall(
                new String[] {"engine-call", "--output", dir.resolve("result.json").toString()},
                (parentJson, parentName, fnName, inputArgs) -> null));

    assertThat(TestConnections.markedModule()).isTrue();
  }

  @Test
  void aFailureEndsStandardErrorWithTheMarkedMessage() {
    String stderr =
        failWith(new IllegalStateException("leaf failed:\n boom"), "the module logged this");

    String[] lines = stderr.split("\n");
    String last = lines[lines.length - 1];
    assertThat(stderr).contains("java.lang.IllegalStateException");
    assertThat(last).startsWith(ModuleDispatcher.FUNCTION_ERROR_MARKER);
    assertThat(decodeMarker(last)).isEqualTo("leaf failed:\n boom");
  }

  /** The generated switch wraps an unknown function in a reflection exception. */
  @Test
  void aReflectionWrapperReportsItsTargetsMessage() {
    String stderr =
        failWith(new InvocationTargetException(new java.lang.Error("unknown function nope")), "");

    assertThat(decodeMarker(lastLine(stderr))).isEqualTo("unknown function nope");
  }

  @Test
  void aFailureWithoutMessageReportsItsType() {
    assertThat(decodeMarker(lastLine(failWith(new NullPointerException(), ""))))
        .isEqualTo("java.lang.NullPointerException");
  }

  @Test
  void aLongMessageIsCutToFitTheStderrTheEngineKeeps() {
    String message = "é\u0001<".repeat(20000);

    String reported = decodeMarker(lastLine(failWith(new RuntimeException(message), "")));

    assertThat(reported).endsWith("...");
    assertThat(message).startsWith(reported.substring(0, reported.length() - 3));
    assertThat(
            lastLine(failWith(new RuntimeException(message), "")).getBytes(StandardCharsets.UTF_8))
        .hasSizeLessThan(100 * 1024);
  }

  @Test
  void aCollectionThatReturnsItselfKeepsItsReceiversBase() throws Exception {
    JSON receiverJson =
        JSON.from("{\"names\":[\"b\",\"a\"],\"__daggerCollectionBase\":\"base-id\"}");
    Items receiver = JsonConverter.fromJSON(receiverJson, Items.class);
    receiver.names = List.of("c");

    JSON result =
        ModuleDispatcher.collectionResult(
            receiverJson, receiver, receiver, JsonConverter.toJSON(receiver));

    assertThat(result.convert())
        .isEqualTo("{\"names\":[\"c\"],\"__daggerCollectionBase\":\"base-id\"}");
  }

  /** Without the base, the engine makes the new instance its own base, so its delta is empty. */
  @Test
  void aNewCollectionDoesNotInheritTheReceiversBase() throws Exception {
    JSON receiverJson =
        JSON.from("{\"names\":[\"b\",\"a\"],\"__daggerCollectionBase\":\"base-id\"}");
    Items receiver = JsonConverter.fromJSON(receiverJson, Items.class);
    Items fresh = new Items();
    fresh.names = List.of("b", "a");

    JSON result =
        ModuleDispatcher.collectionResult(
            receiverJson, receiver, fresh, JsonConverter.toJSON(fresh));

    assertThat(result.convert()).isEqualTo("{\"names\":[\"b\",\"a\"]}");
  }

  @Test
  void aReceiverWithoutBaseLeavesTheResultAsItIs() throws Exception {
    JSON receiverJson = JSON.from("{\"names\":[\"a\"]}");
    Items receiver = JsonConverter.fromJSON(receiverJson, Items.class);
    JSON resultJson = JsonConverter.toJSON(receiver);

    assertThat(ModuleDispatcher.collectionResult(receiverJson, receiver, receiver, resultJson))
        .isSameAs(resultJson);
  }

  public static class Items {
    private List<String> names;

    public Items() {}
  }

  private interface ExitCode {
    int run() throws Exception;
  }

  private static String request(String fnArgs) {
    return "{\"receiverType\":\"Hello\",\"receiverValue\":\"{}\",\"fnName\":\"greet\",\"fnArgs\":"
        + Json.createValue(fnArgs)
        + "}";
  }

  private static String dispatch(String request, ModuleDispatcher.Dispatch dispatch)
      throws Exception {
    return ModuleDispatcher.dispatch(
        new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), dispatch);
  }

  private static String failWith(Throwable failure, String log) {
    var captured = new ByteArrayOutputStream();
    PrintStream savedErr = System.err;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      int code =
          withStdin(
              request("{}"),
              () ->
                  ModuleDispatcher.engineCall(
                      new String[] {"engine-call"},
                      (parentJson, parentName, fnName, inputArgs) -> {
                        System.err.println(log);
                        if (failure instanceof Exception e) {
                          throw e;
                        }
                        throw (java.lang.Error) failure;
                      }));
      assertThat(code).isEqualTo(2);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      System.setErr(savedErr);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  private static int withStdin(String stdin, ExitCode run) throws Exception {
    InputStream savedIn = System.in;
    System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    try {
      return run.run();
    } finally {
      System.setIn(savedIn);
    }
  }

  private static String lastLine(String text) {
    String[] lines = text.split("\n");
    return lines[lines.length - 1];
  }

  private static String decodeMarker(String line) {
    String json = line.substring(ModuleDispatcher.FUNCTION_ERROR_MARKER.length());
    return Json.createReader(new StringReader("[" + json + "]")).readArray().getString(0);
  }
}
