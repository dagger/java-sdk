package com.example.app;

import static io.dagger.client.modules.clientdep.ClientDep.clientDep;

public class App {
  public static void main(String[] args) throws Exception {
    System.out.println(clientDep().greeting());
  }
}
