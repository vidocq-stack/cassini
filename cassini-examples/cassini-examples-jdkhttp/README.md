# cassini-examples-jdkhttp

**Pure Mode A** — Cassini + native JDK transport (`com.sun.net.httpserver`), without any external dependency.

Use case: minimal embedded JAX-RS application (CLI tool, microservice, lightweight container). The only HTTP transport is the one provided by the JDK itself — zero Maven dependency outside `cassini-*` and `jakarta.ws.rs-api`.

## Run

```bash
mvn -pl cassini-examples/cassini-examples-jdkhttp \
    exec:java -Dexec.mainClass=io.vidocq.cassini.examples.jdkhttp.Main
```

or `Main.java` from IntelliJ. The server starts on `http://localhost:8080`.

## Endpoints

Identical to `cassini-examples-chappe`: `/greetings`, `/greetings/{name}`, CRUD `/todos`.

See `src/test/resources/http/jdkhttp-examples.http` for IntelliJ HTTP Client requests.

## How it works

### Manual bootstrap via `CassiniStack`

Unlike the Chappe example, **`SeBootstrap` is not used**. Instead, the stack is bootstrapped manually via the `CassiniStack` SPI:

```java
var stack = CassiniStack.builder()
        .application(new ExamplesApp())
        .build();

var server = new JdkHttpAdapter(stack.adapter()).serve(8080);
```

This is the "low-level" API demonstrating how to integrate Cassini into any third-party transport: obtain a `CassiniHttpAdapter` and plug it into the transport.

### Under the hood — `JdkHttpAdapter`

1. **`JdkHttpAdapter.serve(port)`** creates a JDK `HttpServer` with a virtual-thread executor (one VT per request).
2. For each incoming request, the `HttpHandler`:
   - Builds a `JdkHttpExchange` (impl of `CassiniHttpExchange`) from the `com.sun.net.httpserver.HttpExchange`.
   - Calls `engine.dispatch(exchange)` (the `CassiniHttpAdapter` produced by `CassiniStack`).
   - Reads the response from the exchange (`collectedStatus`, `collectedHeaders`, `collectedBody`).
   - Writes to the JDK `HttpExchange` via `sendResponseHeaders()` + `responseBody().write()`.

### What about `SeBootstrap`?

`cassini-jdk-http` also provides a `JdkHttpRuntimeDelegate` via ServiceLoader, so `SeBootstrap.start()` would work too. However, this example demonstrates the direct API to showcase the decoupling.

**Conflict warning**: if both `cassini-chappe` and `cassini-jdk-http` are on the module path, `RuntimeDelegate.getInstance()` returns the first one found (non-deterministic). Force the choice via:

```bash
java -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.jdkhttp.JdkHttpRuntimeDelegate ...
```

In practice, an application includes only **one** transport in its `pom.xml`.

### Chappe vs native JDK — comparison

| Aspect | Chappe | Native JDK |
|---|---|---|
| HTTP/1.1 keep-alive | ✅ | ✅ |
| HTTP/2 | ✅ | ❌ |
| Chunked streaming | ✅ via `Body.streaming(InputStream)` | ✅ via `sendResponseHeaders(0)` |
| Performance | optimized (zero-copy file, buffer pool) | basic (JDK stock) |
| Dependency | `chappe-core` (~xx KB) | none (bundled in the JDK) |
| Use-case | high-perf applications | tools, embedded, distroless |

## Tests

```bash
mvn -pl cassini-examples/cassini-examples-jdkhttp test
```

10 tests, structurally identical to the Chappe ones — proof that the abstraction works.

## See also

- [`cassini-examples/README.md`](../README.md) — overview and `RuntimeDelegate` mechanism.
- [`cassini-examples-chappe`](../cassini-examples-chappe) — version with Chappe transport.
- [`cassini-examples-vauban`](../cassini-examples-vauban) — Mode B (CDI) + static UI.
