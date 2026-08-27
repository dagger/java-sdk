package io.dagger.modules.app;

import static io.dagger.client.dep.Dep.dep;
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
}
