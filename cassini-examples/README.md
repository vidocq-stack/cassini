# Cassini — Examples

Three examples illustrate the different ways to use Cassini depending on the desired transport and integration.

| Module | Transport | CDI | Bootstrap |
|---|---|---|---|
| `cassini-examples-chappe` | Chappe (HTTP/1.1 + HTTP/2) | no | `SeBootstrap.start()` |
| `cassini-examples-jdkhttp` | Native JDK (`com.sun.net.httpserver`) | no | `CassiniStack.builder()` or `SeBootstrap.start()` |
| `cassini-examples-vauban` | Chappe + Vauban CDI | yes (`@ApplicationScoped` + `@Inject`) | `VaubanContainer` + `SeBootstrap.start()` (auto-discovery `BeanProvider`) |

All three expose the same resources for direct comparison:
- `GET /greetings` → `"Hello, World!"`
- `GET /greetings/{name}` → `"Hello, {name}!"`
- `GET|POST|PUT|DELETE /todos[/{id}]` → CRUD JSON

## How to run

Each module has a `Main.java` launchable from IntelliJ or via:

```bash
mvn -pl cassini-examples/cassini-examples-{chappe,jdkhttp,vauban} \
    exec:java -Dexec.mainClass=io.vidocq.cassini.examples.{chappe,jdkhttp,vauban}.Main
```

The `.http` files in `src/test/resources/http/` allow testing via the IntelliJ HTTP Client.

---

## Transport discovery mechanism — `SeBootstrap`

`jakarta.ws.rs.SeBootstrap.start(app, config)` uses the standard JPMS ServiceLoader to find an implementation of `jakarta.ws.rs.ext.RuntimeDelegate`:

```
SeBootstrap.start(app, config)
    ↓
RuntimeDelegate.getInstance()
    ↓
ServiceLoader.load(RuntimeDelegate.class)   ← looks for modules with
                                                provides RuntimeDelegate with ...
    ↓
First provider found → used
```

### Where it is declared in Cassini

| Module | Provider | Declaration |
|---|---|---|
| `cassini-chappe` | `ChappeRuntimeDelegate` | `module-info.java`: `provides jakarta.ws.rs.ext.RuntimeDelegate with ChappeRuntimeDelegate;` |
| `cassini-jdk-http` | `JdkHttpRuntimeDelegate` | `module-info.java`: `provides jakarta.ws.rs.ext.RuntimeDelegate with JdkHttpRuntimeDelegate;` |
| `cassini-core` | (none) | The internal `CassiniRuntimeDelegate` is **not** exposed via ServiceLoader to avoid conflicts — that is the transport's responsibility. |

### Conflict if multiple transports on the classpath

If both `cassini-chappe` and `cassini-jdk-http` are in the module graph, the ServiceLoader returns the **first** one found — non-deterministic behavior.

**Solution**: force the choice via the standard JAX-RS system property:

```bash
java -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.chappe.ChappeRuntimeDelegate ...
```

or the reverse for JDK:

```bash
java -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.jdkhttp.JdkHttpRuntimeDelegate ...
```

In practice, an application uses only **one** transport: include only one of the two artifacts (`cassini-chappe` OR `cassini-jdk-http`) in the `pom.xml`.

---

## `BeanProvider` discovery (DI integration)

Cassini exposes a public SPI `io.vidocq.cassini.spi.bean.BeanProvider` allowing a DI container (Vauban CDI, Weld, OpenWebBeans, or any other mechanism) to provide managed instances of JAX-RS resources and providers, **without `cassini-api` or `cassini-core` depending on `jakarta.cdi`**.

```
CassiniStack.builder()
    ↓
ServiceLoader.load(BeanProvider.Factory.class)
    ↓ (max priority())
Factory.create() → BeanProvider
    ↓ used to instantiate @Path / @Provider
```

| Adapter | Container | Priority |
|---|---|---|
| `cassini-cdi-vauban` | Vauban CDI (`io.vidocq.vauban`) | 100 |
| (future) `cassini-cdi-weld` | Weld | — |
| (future) `cassini-cdi-owb` | OpenWebBeans | — |

When a `BeanProvider` is active:

- `BeanProvider.getResourceClasses()` is merged with `Application.getClasses()` and `getSingletons()` — the user can therefore pass an empty `new Application() {}`.
- The internal resolver first tries `beanProvider.getBean(cls)`. If the class is not managed (`IllegalArgumentException`), it falls back to `ResourceFactory` then to `newInstance()`.
- Auto-discovery can be disabled via `CassiniStack.builder().beanProvider(null)`.

---

## Manual bootstrap — `CassiniStack`

For cases where **`SeBootstrap` is not desired** (e.g., to fine-tune the transport, integrate into an existing framework, or in a test pipeline), manual bootstrapping is available:

```java
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;

var stack = CassiniStack.builder()
    .application(new MyApplication())
    .beanProvider(myBeanProvider)               // optional — otherwise auto-discovery
    .provider(new MyExceptionMapper())          // optional
    .build();

CassiniHttpAdapter adapter = stack.adapter();
// adapter.dispatch(exchange) — call from your transport
```

`CassiniStack.builder()` is available in `cassini-api` (the public SPI). This interface is implemented by `cassini-core` via ServiceLoader (`provides CassiniStack.BuilderFactory`).

### Use case per example

#### `cassini-examples-chappe` — standard `SeBootstrap`

```java
SeBootstrap.start(new ExamplesApp(),
    SeBootstrap.Configuration.builder()
        .host("0.0.0.0").port(8080).build());
```

The Chappe `RuntimeDelegate` is found automatically. No manual bootstrap.

#### `cassini-examples-jdkhttp` — direct `CassiniStack`

```java
var stack = CassiniStack.builder().application(new ExamplesApp()).build();
var server = new JdkHttpAdapter(stack.adapter()).serve(8080);
```

`SeBootstrap` is bypassed to demonstrate the low-level API. But since `JdkHttpRuntimeDelegate` is also registered, **`SeBootstrap.start()` could be used instead**.

#### `cassini-examples-vauban` — CDI + `SeBootstrap`

```java
// 1. Start Vauban CDI (registers as CDI.current() via VaubanCDIProvider)
var container = VaubanContainer.builder()
        .addBeanClass(TodoService.class)
        .addBeanClass(TodoResource.class)
        .build();

// 2. SeBootstrap — Application is empty. Cassini discovers the Vauban BeanProvider
//    via ServiceLoader, scans the @Path/@Provider classes managed
//    by the container and instantiates them via resolved @Inject.
SeBootstrap.start(new Application() {}, config);
```

No manual singleton list: `VaubanBeanProvider.getResourceClasses()` iterates the `BeanManager` and exposes all `@Path`/`@Provider`-annotated classes to the stack. The internal resolver calls `container.select()` on each dispatch (respects `@RequestScoped` etc.).

---

## Dependency architecture

```
              ┌─ cassini-api (public SPI)
              │     ├── CassiniHttpExchange, CassiniHttpAdapter
              │     ├── CassiniStack, ResourceFactory
              │     └── (zero dependency outside jakarta.ws.rs-api)
              │
              ├─ cassini-core (implementation, closed)
              │     ├── Invoker, UriRouter, ResourceScanner...
              │     ├── CassiniRuntimeDelegate (base for transports)
              │     └── exports internal.runtime to {tck, jdkhttp}
              │
   example ───┼─ cassini-chappe ──→ requires cassini-api + cassini-core (ServiceLoader)
              │     provides RuntimeDelegate with ChappeRuntimeDelegate
              │
   example ───┼─ cassini-jdk-http ──→ requires cassini-api + cassini-core
              │     provides RuntimeDelegate with JdkHttpRuntimeDelegate
              │
   example ───┴─ cassini-cdi-vauban ──→ requires cassini-api + io.vidocq.vauban.core
                    provides BeanProvider.Factory with VaubanBeanProviderFactory
```

**Golden rule**: `cassini-chappe`, `cassini-jdk-http`, and `cassini-cdi-vauban` import **no internal packages** from `cassini-core`. They only use the public SPI (`cassini-api` + `CassiniStack` + `BeanProvider`). The `requires cassini-core` is not even needed for `cassini-cdi-vauban`, which only speaks to `BeanProvider` (public SPI).

This discipline allows any ecosystem (Vidocq, Weld, Quarkus, other) to write its own transport or DI integration **without accessing Cassini internals**.

---

## Automated tests

Each example has an `ExampleServer` class (AutoCloseable) used by JUnit 5 tests:

```java
@BeforeAll
static void start() throws Exception { server = new ExampleServer(); }

@AfterAll
static void close() throws Exception { server.close(); }

@Test
void listEmpty() throws Exception {
    var resp = http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
}
```

The server starts on a random port (`ServerSocket(0)`), ensuring isolation between parallel tests.
