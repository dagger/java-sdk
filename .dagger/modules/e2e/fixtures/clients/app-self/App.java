package io.dagger.modules.app;

import static io.dagger.client.app.App.app;
import static io.dagger.client.dep.Dep.dep;
import static io.dagger.client.greeter.Greeter.greeter;
import static io.dagger.sdk.Dagger.dag;

import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Object;
import io.dagger.sdk.exception.DaggerQueryException;
import java.util.concurrent.ExecutionException;

@Object
public class App {
  /** A call on a dependency, through its generated client. */
  @Function
  public String greetViaDep(String name)
      throws InterruptedException, ExecutionException, DaggerQueryException {
    return dep(dag()).greet(name);
  }

  /** A core type returned by the dependency's client, used through core: the same Java type. */
  @Function
  public String depFileViaCore()
      throws InterruptedException, ExecutionException, DaggerQueryException {
    return dep(dag()).scratch().file("dep.txt").contents();
  }

  /** The same dependency under an alias: a second client, on the same session. */
  @Function
  public String greetViaAlias(String name)
      throws InterruptedException, ExecutionException, DaggerQueryException {
    return greeter(dag()).greet(name);
  }

  /** A self call, through this module's own generated client. */
  @Function
  public String greetSelf(String name)
      throws InterruptedException, ExecutionException, DaggerQueryException {
    return app(dag()).greetViaDep(name);
  }
}
