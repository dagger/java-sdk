package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dagger.client.exception.DaggerExecException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModuleDispatcherTest {

  @Test
  void argumentValuesReachTheDispatcherVerbatim() throws Exception {
    var seen = new Object() {
      Map<String, JSON> args;
      String parentName;
      String fnName;
      JSON parentJson;
    };

    String out =
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
    assertThat(out).isEqualTo("\"Hi, World\"\n");
  }

  /**
   * A constructor call names its owning type and an empty function name, and carries no receiver
   * state. The generated dispatcher expects an empty object, not null.
   */
  @Test
  void aConstructorCallCarriesItsTypeAndAnEmptyReceiver() throws Exception {
    var seen = new String[3];

    dispatch(
        // The engine marshals a constructor's absent receiver as the JSON text "null",
        // which reaches the request as a string, not as a JSON null.
        "{\"receiverType\":\"Hello\",\"receiverValue\":\"null\",\"fnName\":\"\",\"fnArgs\":\"{}\"}",
        (parentJson, parentName, fnName, inputArgs) -> {
          seen[0] = parentName;
          seen[1] = fnName;
          seen[2] = parentJson.convert();
          return JSON.from("null");
        });

    assertThat(seen[0]).isEqualTo("Hello");
    assertThat(seen[1]).isEmpty();
    assertThat(seen[2]).isEqualTo("null");
  }

  @Test
  void aRequestWithNoReceiverTypeIsRejected() {
    assertThatThrownBy(
            () ->
                dispatch(
                    "{\"fnName\":\"greet\",\"fnArgs\":\"{}\"}",
                    (parentJson, parentName, fnName, inputArgs) -> JSON.from("null")))
        .hasMessageContaining("receiverType");
  }

  @Test
  void anythingAfterTheRequestObjectIsRejected() {
    assertThatThrownBy(
            () ->
                dispatch(
                    "{\"receiverType\":\"Hello\",\"fnName\":\"greet\",\"fnArgs\":\"{}\"}{}",
                    (parentJson, parentName, fnName, inputArgs) -> JSON.from("null")))
        .hasMessageContaining("trailing data");
  }

  @Test
  void aRequestWithNoFunctionNameIsRejected() {
    assertThatThrownBy(
            () ->
                dispatch(
                    "{\"receiverType\":\"Hello\",\"fnArgs\":\"{}\"}",
                    (parentJson, parentName, fnName, inputArgs) -> JSON.from("null")))
        .hasMessageContaining("fnName");
  }

  @Test
  void anAbsentReceiverValueBecomesAnEmptyObject() throws Exception {
    var seen = new String[1];

    dispatch(
        "{\"receiverType\":\"Hello\",\"fnName\":\"\",\"fnArgs\":\"{}\"}",
        (parentJson, parentName, fnName, inputArgs) -> {
          seen[0] = parentJson.convert();
          return JSON.from("null");
        });

    assertThat(seen[0]).isEqualTo("{}");
  }

  @Test
  void trailingDataInsideTheArgumentsTextIsRejected() {
    assertThatThrownBy(
            () ->
                dispatch(
                    "{\"receiverType\":\"Hello\",\"fnName\":\"greet\",\"fnArgs\":\"{}{}\"}",
                    (parentJson, parentName, fnName, inputArgs) -> JSON.from("null")))
        .hasMessageContaining("fnArgs");
  }

  @Test
  void malformedReceiverTextIsRejectedEvenWhenTheCallWouldIgnoreIt() {
    assertThatThrownBy(
            () ->
                dispatch(
                    "{\"receiverType\":\"Hello\",\"receiverValue\":\"{\",\"fnName\":\"\",\"fnArgs\":\"{}\"}",
                    (parentJson, parentName, fnName, inputArgs) -> JSON.from("null")))
        .hasMessageContaining("receiverValue");
  }

  /** fnArgs is JSON text, so an inline object rather than a string is a malformed request. */
  @Test
  void argumentsSentAsAnInlineObjectRatherThanJsonTextAreRejected() {
    assertThatThrownBy(
            () ->
                dispatch(
                    "{\"receiverType\":\"Hello\",\"fnName\":\"greet\",\"fnArgs\":{\"name\":\"World\"}}",
                    (parentJson, parentName, fnName, inputArgs) -> JSON.from("null")))
        .hasMessageContaining("fnArgs");
  }

  @Test
  void aRequestWithNoArgumentsObjectIsRejected() {
    assertThatThrownBy(
            () ->
                dispatch(
                    "{\"receiverType\":\"Hello\",\"fnName\":\"greet\"}",
                    (parentJson, parentName, fnName, inputArgs) -> JSON.from("null")))
        .hasMessageContaining("fnArgs");
  }

  @Test
  void moduleCodePrintingToStandardOutputDoesNotCorruptTheResult() throws Exception {
    String out =
        dispatch(
            "{\"receiverType\":\"Hello\",\"fnName\":\"greet\",\"fnArgs\":\"{}\"}",
            (parentJson, parentName, fnName, inputArgs) -> {
              System.out.println("building something");
              return JSON.from("\"done\"");
            });

    assertThat(out).isEqualTo("\"done\"\n");
  }

  @Test
  void aPlainFailureReportsItsMessageAndTypeOnStandardError() {
    var report = failWith(new IllegalStateException("no such thing"));

    assertThat(report.exitCode).isEqualTo(2);
    assertThat(report.stderr)
        .contains("\"message\":\"no such thing\"")
        .contains("\"type\":\"java.lang.IllegalStateException\"");
  }

  @Test
  void aReflectionWrapperReportsItsTargetsMessage() {
    // Error is io.dagger.client.Error in this package; the wrapper carries the JDK one.
    var report =
        failWith(new InvocationTargetException(new java.lang.Error("unknown function nope")));

    assertThat(report.stderr)
        .contains("\"message\":\"unknown function nope\"")
        .contains("\"type\":\"java.lang.Error\"");
  }

  @Test
  void anExecFailureReportsWhatRanAndWhatItPrinted() {
    var report = failWith(new StubExecException());

    assertThat(report.stderr)
        .contains("\"stdout\":\"out\"")
        .contains("\"stderr\":\"err\"")
        .contains("\"cmd\":[\"sh\",\"-c\",\"false\"]")
        .contains("\"exitCode\":1")
        .contains("\"path\":[\"container\",\"stdout\"]");
  }

  /**
   * The exec accessors read GraphQL error extensions the engine sends. An exception carrying none
   * must still produce an envelope rather than failing inside the failure path.
   */
  @Test
  void anExecFailureWithNoEngineExtensionsStillReports() {
    var report = failWith(new DaggerExecException());

    assertThat(report.exitCode).isEqualTo(2);
    assertThat(report.stderr).contains("\"type\":\"io.dagger.client.exception.DaggerExecException\"");
  }

  private static String dispatch(String request, ModuleDispatcher.Dispatch dispatch)
      throws Exception {
    var out = new ByteArrayOutputStream();
    ModuleDispatcher.engineCall(
        new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        dispatch);
    return out.toString(StandardCharsets.UTF_8);
  }

  private record Report(int exitCode, String stderr) {}

  private static Report failWith(Exception failure) {
    var captured = new ByteArrayOutputStream();
    PrintStream savedErr = System.err;
    var savedIn = System.in;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    System.setIn(
        new ByteArrayInputStream(
            "{\"receiverType\":\"Hello\",\"fnName\":\"greet\",\"fnArgs\":\"{}\"}"
                .getBytes(StandardCharsets.UTF_8)));
    try {
      int code =
          ModuleDispatcher.engineCallMain(
              (parentJson, parentName, fnName, inputArgs) -> {
                throw failure;
              });
      return new Report(code, captured.toString(StandardCharsets.UTF_8));
    } finally {
      System.setErr(savedErr);
      System.setIn(savedIn);
    }
  }

  private static final class StubExecException extends DaggerExecException {
    @Override
    public String getStdOut() {
      return "out";
    }

    @Override
    public String getStdErr() {
      return "err";
    }

    @Override
    public List<String> getCmd() {
      return List.of("sh", "-c", "false");
    }

    @Override
    public Integer getExitCode() {
      return 1;
    }

    @Override
    public List<String> getPath() {
      return List.of("container", "stdout");
    }
  }
}
