# cassini-examples-chappe

**Pure Mode A** — Cassini + Chappe transport, without CDI.

This is the simplest use case: a standalone JAX-RS application bootstrapped via the standard `SeBootstrap` API, where each resource is instantiated via its no-arg constructor on each request.

## Run

```bash
mvn -pl cassini-examples/cassini-examples-chappe \
    exec:java -Dexec.mainClass=io.vidocq.cassini.examples.chappe.Main
```

or launch `Main.java` directly from IntelliJ.

The server starts on `http://localhost:8080`.

## Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/greetings` | "Hello, World!" (text/plain) |
| `GET` | `/greetings/{name}` | "Hello, {name}!" |
| `GET` | `/todos` | Todo list (JSON) |
| `POST` | `/todos` | Creates a todo, returns 201 + JSON |
| `GET` | `/todos/{id}` | Fetches a todo, 404 if not found |
| `PUT` | `/todos/{id}` | Updates a todo |
| `DELETE` | `/todos/{id}` | Deletes a todo, 204 |

The file `src/test/resources/http/chappe-examples.http` contains all requests for testing via the IntelliJ HTTP Client.

## How it works

### Bootstrap

`Main.java` uses the standard public `SeBootstrap` API:

```java
SeBootstrap.start(new ExamplesApp(),
    SeBootstrap.Configuration.builder()
        .host("0.0.0.0").port(8080).build());
```

Under the hood:

1. **`SeBootstrap.start()`** calls `RuntimeDelegate.getInstance().bootstrap(app, config)`.
2. **`RuntimeDelegate.getInstance()`** uses `ServiceLoader.load(RuntimeDelegate.class)` which finds `ChappeRuntimeDelegate` (declared in `cassini-chappe/module-info.java` via `provides RuntimeDelegate with ChappeRuntimeDelegate`).
3. **`ChappeRuntimeDelegate.bootstrap()`** calls `CassiniStack.builder().application(app).build()` to assemble the JAX-RS stack, then creates a Chappe `Server` and attaches a `ChappeHttpAdapter` pointing to the stack.
4. **`ChappeHttpAdapter`** converts Chappe `Request`/`Response` objects to `CassiniHttpExchange` and delegates to `CassiniHttpAdapter.dispatch()`.

### No CDI

No `BeanProvider` is on the classpath, so auto-discovery in `CassiniStack.builder()` finds nothing. The resolver falls back to "Mode A": `clazz.getDeclaredConstructor().newInstance()` on each request. Resources must have a public no-arg constructor.

### Todo storage

`TodoResource` uses a static `ConcurrentHashMap` — data lives in the JVM, shared across all requests. For a real use case an injected service should be used (see `cassini-examples-vauban`).

## Tests

```bash
mvn -pl cassini-examples/cassini-examples-chappe test
```

10 tests: `GreetingResourceTest` (3) + `TodoResourceTest` (7).

`ExampleServer` starts Cassini on a random port for each test suite; the JDK `HttpClient` sends real HTTP requests.

## See also

- [`cassini-examples-jdkhttp`](../cassini-examples-jdkhttp) — same thing but without Chappe (native JDK transport).
- [`cassini-examples-vauban`](../cassini-examples-vauban) — version with Vauban CDI + static UI.
- [`cassini-examples/README.md`](../README.md) — overview and transport discovery mechanism.
