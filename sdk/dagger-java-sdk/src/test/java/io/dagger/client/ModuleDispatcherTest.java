package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dagger.client.engineconn.TestConnections;
import io.dagger.client.exception.DaggerExecException;
import io.dagger.client.exception.DaggerQueryException;
import io.dagger.client.graphql.GraphQLErrors;
import io.dagger.client.modules.core.JsonConverter;
import io.dagger.modules.demo.DemoModule;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
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
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleDispatcherTest {

  /**
   * A stand-in for the generated JSON scalar, whose class name depends on the schema the SDK was
   * generated from: the dispatcher takes any.
   */
  static final class TestJson extends Scalar<String> {
    TestJson(String value) {
      super(value);
    }

    static TestJson from(String value) {
      return new TestJson(value);
    }
  }

  @Test
  void theRequestReachesTheSwitchVerbatim() throws Exception {
    var seen =
        new Object() {
          Map<String, TestJson> args;
          String parentName;
          String fnName;
          TestJson parentJson;
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
              return TestJson.from("\"Hi, World\"");
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
          return TestJson.from("{}");
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
    ModuleDispatcher.Dispatch<TestJson> never =
        (parentJson, parentName, fnName, inputArgs) -> TestJson.from("null");

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
                    TestJson::from,
                    (parentJson, parentName, fnName, inputArgs) -> {
                      System.out.println("a log line of the module");
                      return TestJson.from("[\"a\",\"b\"]");
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
                TestJson::from,
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

  private static final String ORIGIN =
      "[traceparent:0123456789abcdef0123456789abcdef-0123456789abcdef]";

  private static DaggerExecException execError() {
    return new DaggerExecException(
        GraphQLErrors.of(
            "{\"message\":\"exit code: 1 "
                + ORIGIN
                + "\",\"path\":[\"container\",\"stdout\"],\"extensions\":{\"_type\":\"EXEC_ERROR\","
                + "\"cmd\":[\"sh\",\"-c\",\"exit 1\"],\"exitCode\":1,\"stdout\":\"out\","
                + "\"stderr\":\"err\",\"nested\":{\"ok\":true,\"none\":null}}}"));
  }

  private static Void throwExec() throws DaggerExecException {
    throw execError();
  }

  private static JsonObject readReport(Path file) throws Exception {
    return Json.createReader(new StringReader(Files.readString(file))).readObject();
  }

  @Test
  void aFailureIsReportedInTheErrorFileAndNotMarked(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("error.json");

    String stderr = failWith(new IllegalStateException("leaf failed:\n boom"), "logged", file);

    assertThat(readReport(file))
        .isEqualTo(
            Json.createObjectBuilder()
                .add("message", "leaf failed:\n boom")
                .add("values", JsonValue.EMPTY_JSON_OBJECT)
                .build());
    assertThat(stderr).doesNotContain(ModuleDispatcher.FUNCTION_ERROR_MARKER);
    assertThat(stderr).startsWith("logged\njava.lang.IllegalStateException: leaf failed:\n boom\n");
  }

  /** The engine shows an API error at its origin, with its logs, so nothing is printed. */
  @Test
  void anApiErrorWithAnOriginIsReportedWithTheGraphQLErrorsExtensionsAndNotPrinted(
      @TempDir Path dir) throws Exception {
    Path file = dir.resolve("error.json");

    String stderr = failWith(execError(), "logged", file);

    JsonObject report = readReport(file);
    assertThat(report.getString("message")).isEqualTo("exit code: 1 " + ORIGIN);
    assertThat(report.getJsonObject("values"))
        .isEqualTo(
            Json.createReader(
                    new StringReader(
                        "{\"_type\":\"EXEC_ERROR\",\"cmd\":[\"sh\",\"-c\",\"exit 1\"],"
                            + "\"exitCode\":1,\"stdout\":\"out\",\"stderr\":\"err\","
                            + "\"nested\":{\"ok\":true,\"none\":null}}"))
                .readObject());
    assertThat(stderr).isEqualTo("logged\n");
  }

  /**
   * What {@code failing} throws, run on a thread of its own, so its stack trace holds no frames of
   * the test runner's, as a module's holds none below the SDK's.
   */
  private static Exception thrownBy(Callable<?> failing) throws InterruptedException {
    Exception[] thrown = new Exception[1];
    Thread thread =
        new Thread(
            () -> {
              try {
                failing.call();
              } catch (Exception e) {
                thrown[0] = e;
              }
            });
    thread.start();
    thread.join();
    return thrown[0];
  }

  @Test
  void anApiErrorWithoutAnOriginPrintsItself(@TempDir Path dir) throws Exception {
    Exception failure =
        thrownBy(
            () -> {
              throw new DaggerQueryException(GraphQLErrors.of("{\"message\":\"bad argument\"}"));
            });

    String stderr = failWith(failure, "", dir.resolve("error.json"));

    assertThat(stderr)
        .isEqualTo("\nio.dagger.client.exception.DaggerQueryException: bad argument\n");
  }

  /** The module's own frames remain; the SDK's and the JDK's go. */
  @Test
  void anErrorOfTheModulesOwnPrintsAShortStackTrace(@TempDir Path dir) throws Exception {
    Exception failure = thrownBy(DemoModule::failNatively);

    String stderr = failWith(failure, "", dir.resolve("error.json"));

    assertThat(stderr)
        .matches(
            "\njava.lang.Exception: demo failed natively\n"
                + "\tat io\\.dagger\\.modules\\.demo\\.DemoModule\\.failNatively"
                + "\\(DemoModule\\.java:\\d+\\)\n");
  }

  /** A wrapped API error is the module's own: its chain prints, without the origins. */
  @Test
  void aWrappedApiErrorPrintsItsChainWithoutOrigins(@TempDir Path dir) throws Exception {
    Exception failure = thrownBy(() -> DemoModule.wrap(thrownBy(ModuleDispatcherTest::throwExec)));
    Path file = dir.resolve("error.json");

    String stderr = failWith(failure, "", file);

    assertThat(stderr)
        .matches(
            "\njava.lang.Exception: demo ran a failing command: exit code: 1\n"
                + "\tat io\\.dagger\\.modules\\.demo\\.DemoModule\\.wrap"
                + "\\(DemoModule\\.java:\\d+\\)\n"
                + "Caused by: io\\.dagger\\.client\\.exception\\.DaggerExecException: exit code:"
                + " 1\n");
    JsonObject report = readReport(file);
    assertThat(report.getString("message"))
        .isEqualTo("demo ran a failing command: exit code: 1 " + ORIGIN);
    assertThat(report.getJsonObject("values").getInt("exitCode")).isEqualTo(1);
  }

  @Test
  void anUnwritableErrorFileFallsBackToTheMarker(@TempDir Path dir) {
    String stderr =
        failWith(new IllegalStateException("leaf failed"), "", dir.resolve("missing/error.json"));

    assertThat(decodeMarker(lastLine(stderr))).isEqualTo("leaf failed");
  }

  /** Long strings keep their ends, and the message its start and the origins it names. */
  @Test
  void aReportOverTheLimitIsShortened(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("error.json");
    String message = "é".repeat(ModuleDispatcher.FUNCTION_ERROR_FILE_LIMIT) + " " + ORIGIN;

    failWith(new RuntimeException(message), "", file);

    assertThat(Files.size(file)).isLessThanOrEqualTo(ModuleDispatcher.FUNCTION_ERROR_FILE_LIMIT);
    String reported = readReport(file).getString("message");
    assertThat(reported).startsWith("éé").endsWith("..." + " " + ORIGIN);
  }

  @Test
  void aCollectionThatReturnsItselfKeepsItsReceiversBase() throws Exception {
    TestJson receiverJson =
        TestJson.from("{\"names\":[\"b\",\"a\"],\"__daggerCollectionBase\":\"base-id\"}");
    Items receiver = JsonConverter.fromJSON(receiverJson.convert(), Items.class);
    receiver.names = List.of("c");

    TestJson result =
        ModuleDispatcher.collectionResult(
            receiverJson, receiver, receiver, toJson(receiver), TestJson::from);

    assertThat(result.convert())
        .isEqualTo("{\"names\":[\"c\"],\"__daggerCollectionBase\":\"base-id\"}");
  }

  /** Without the base, the engine makes the new instance its own base, so its delta is empty. */
  @Test
  void aNewCollectionDoesNotInheritTheReceiversBase() throws Exception {
    TestJson receiverJson =
        TestJson.from("{\"names\":[\"b\",\"a\"],\"__daggerCollectionBase\":\"base-id\"}");
    Items receiver = JsonConverter.fromJSON(receiverJson.convert(), Items.class);
    Items fresh = new Items();
    fresh.names = List.of("b", "a");

    TestJson result =
        ModuleDispatcher.collectionResult(
            receiverJson, receiver, fresh, toJson(fresh), TestJson::from);

    assertThat(result.convert()).isEqualTo("{\"names\":[\"b\",\"a\"]}");
  }

  @Test
  void aReceiverWithoutBaseLeavesTheResultAsItIs() throws Exception {
    TestJson receiverJson = TestJson.from("{\"names\":[\"a\"]}");
    Items receiver = JsonConverter.fromJSON(receiverJson.convert(), Items.class);
    TestJson resultJson = toJson(receiver);

    assertThat(
            ModuleDispatcher.collectionResult(
                receiverJson, receiver, receiver, resultJson, TestJson::from))
        .isSameAs(resultJson);
  }

  private static TestJson toJson(Object value) throws Exception {
    return TestJson.from(JsonConverter.toJSON(value).convert());
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

  private static String dispatch(String request, ModuleDispatcher.Dispatch<TestJson> dispatch)
      throws Exception {
    return ModuleDispatcher.dispatch(
        new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
        TestJson::from,
        dispatch);
  }

  private static String failWith(Throwable failure, String log) {
    return failWith(failure, log, null);
  }

  private static String failWith(Throwable failure, String log, Path errorFile) {
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
                      TestJson::from,
                      (parentJson, parentName, fnName, inputArgs) -> {
                        System.err.println(log);
                        if (failure instanceof Exception e) {
                          throw e;
                        }
                        throw (java.lang.Error) failure;
                      },
                      errorFile));
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
