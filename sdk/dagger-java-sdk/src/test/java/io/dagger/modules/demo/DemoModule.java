package io.dagger.modules.demo;

/** Code of a module's own, outside the SDK's packages, whose frames a short stack trace keeps. */
public final class DemoModule {

  private DemoModule() {}

  public static Void failNatively() throws Exception {
    throw new Exception("demo failed natively");
  }

  public static Void wrap(Exception apiError) throws Exception {
    throw new Exception("demo ran a failing command: " + apiError.getMessage(), apiError);
  }
}
