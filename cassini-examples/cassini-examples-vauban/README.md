# cassini-examples-vauban

**Mode B (CDI)** — Cassini + Vauban CDI + static HTML/CSS/JS UI served by Chappe.

End-to-end demo: `http://localhost:8080/` displays a real web page that consumes the REST API at `/api/*`. JAX-RS resources are CDI beans managed by Vauban, with injection (`@Inject TodoService`) resolved automatically.

## Run

```bash
mvn -pl cassini-examples/cassini-examples-vauban \
    exec:java -Dexec.mainClass=io.vidocq.cassini.examples.vauban.Main
```

or `Main.java` from IntelliJ. Open `http://localhost:8080/` in your browser.

```
┌──────────────────────────────────────────────┐
│ Cassini + Vauban CDI — started on port 8080  │
├──────────────────────────────────────────────┤
│  UI  → http://localhost:8080/                │
│  API → http://localhost:8080/api/todos       │
│        http://localhost:8080/api/greetings   │
└──────────────────────────────────────────────┘
```

## Architecture

```
                         Chappe Server :8080
                                │
                                ▼
                    VaubanApp.composeHandler()
                         (composite handler)
                                │
              ┌─────────────────┴──────────────────┐
              │                                    │
        path = /api/*                       other path
              │                                    │
              ▼                                    ▼
     strip /api + dispatch              staticHandler()
              │                                    │
              ▼                                    ▼
     ChappeHttpAdapter             classpath:/static/
              │                       (index.html, style.css, app.js)
              ▼
       CassiniHttpAdapter
              │
              ▼
       Invoker (Cassini)
              │
              ▼
   resolver = beanProvider.getBean(cls)
              │
              ▼
   VaubanContainer.select(TodoResource.class)
              │
              ▼
   instance with @Inject TodoService resolved
```

## How it works

### 1. Vauban CDI bootstrap

```java
var container = VaubanContainer.builder()
        .scanClasspath()
        .build();
```

`scanClasspath()` reads `META-INF/vauban-beans.list`, an index file **generated at compile time** by `vauban-maven-plugin:generate` that lists all CDI-annotated classes (`@ApplicationScoped`, `@RequestScoped`, etc.) in the module.

```
# META-INF/vauban-beans.list — generated
io.vidocq.cassini.examples.vauban.resource.GreetingResource
io.vidocq.cassini.examples.vauban.resource.TodoResource
io.vidocq.cassini.examples.vauban.service.TodoService
```

**Benefits**:
- No runtime classpath scan, no package-level reflection
- Works in Java Modules named modules (`META-INF/` resources are always accessible via `ClassLoader.getResources()`, unlike directory scanning)
- Compile-time detection: if a bean is incorrectly annotated, the plugin reports it immediately
- Reusable list: `scanClasspath()` aggregates all `vauban-beans.list` files from the classpath, so multi-module setups are supported

`VaubanContainer.build()` registers the instance as `CDI.current()` (via `VaubanCDIProvider` ServiceLoader).

#### Plugin configuration

```xml
<plugin>
    <groupId>io.vidocq.vauban</groupId>
    <artifactId>vauban-maven-plugin</artifactId>
    <version>${vauban.version}</version>
    <executions>
        <execution>
            <id>generate</id>
            <goals><goal>generate</goal></goals>
        </execution>
    </executions>
</plugin>
```

> The `addBeanClass()` alternative (explicit declaration) remains available for cases where configuring the Maven plugin is not desired or possible.

### 2. Auto-discovery of `BeanProvider`

`CassiniStack.builder()` (called by `VaubanApp.composeHandler()`) calls `ServiceLoader.load(BeanProvider.Factory.class)` and finds **`VaubanBeanProviderFactory`** (priority 100, declared in `cassini-cdi-vauban/module-info.java`). The factory retrieves `VaubanContainer.current()` and injects it into the stack.

From that point, the internal resolver becomes:
```java
clazz -> beanProvider.getBean(clazz)  // → container.select(clazz) with @Inject resolved
```

The JAX-RS `Application` can be **empty** (`new Application() {}`): `BeanProvider.getResourceClasses()` iterates the Vauban `BeanManager` and exposes all `@Path`/`@Provider`-annotated classes to Cassini.

### 3. Chappe composite handler

`Main.java` does not go through `SeBootstrap` because we want to mix static files + REST API on the same port. Instead:

```java
var server = Server.builder()
        .host("0.0.0.0").port(8080)
        .handler(VaubanApp.composeHandler())
        .build();
```

`VaubanApp.composeHandler()` returns a Chappe `Handler` that dispatches by path:

```java
return req -> {
    if (req.path().startsWith("/api")) {
        // strip /api and delegate to ChappeHttpAdapter (pointing to CassiniStack)
        return cassiniHandler.handle(stripContext(req, "/api", ...));
    }
    return staticHandler.handle(req);  // serves classpath:/static/...
};
```

### 4. Static file serving — Chappe `StaticFileHandler`

Chappe natively provides a `StaticFileHandler` with classpath support, fallback chain, and in-memory cache:

```java
StaticFileHandler.builder()
        .addClasspath("static")    // resolved from classpath:/static/
        .cacheInMemory(true)       // cache in RAM for resources < 64 KB
        .build();
```

> **Minor adjustment needed**: the composite handler rewrites `/` to `/index.html` before passing the request to `StaticFileHandler`. In classpath mode, `getResource("static/")` returns a non-null directory URL, which prevents the internal `indexFile` fallback — the rewrite in the composite handler works around this.

### 5. Client UI

`src/main/resources/static/` contains:

| File | Role |
|---|---|
| `index.html` | Page structure: header, greeting, form, list, footer |
| `style.css` | Modern dark theme (gradient, glow accent, subtle animations) |
| `app.js` | Fetch API: GET `/api/todos`, POST/PUT/DELETE, dynamic render |

The JS calls:
- `GET /api/greetings/Vauban` on load → displays message in the header
- `GET /api/todos` on load → renders the list
- `POST /api/todos` on form submit → creates then re-renders
- `PUT /api/todos/{id}` on checkbox toggle → marks done
- `DELETE /api/todos/{id}` on ✕ click → deletes then re-renders

### 6. Server side

```java
// TodoService.java
@ApplicationScoped
public class TodoService {
    private final Map<Long, Todo> store = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);
    // CRUD methods…
}

// TodoResource.java
@Path("/todos")
@ApplicationScoped
public class TodoResource {
    @Inject TodoService service;
    // @GET, @POST, @PUT, @DELETE…
}
```

When a request arrives, `Invoker` calls `beanProvider.getBean(TodoResource.class)` → Vauban returns the CDI proxy with `service` already injected. The `@ApplicationScoped` scope guarantees a single shared instance.

## Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | HTML UI (index.html) |
| `GET` | `/style.css` | CSS |
| `GET` | `/app.js` | Client JS |
| `GET` | `/api/greetings/{name}` | "Hello, {name}!" |
| `GET` | `/api/todos` | JSON list |
| `POST` | `/api/todos` | Create |
| `GET` | `/api/todos/{id}` | Fetch |
| `PUT` | `/api/todos/{id}` | Update |
| `DELETE` | `/api/todos/{id}` | Delete |

See `src/test/resources/http/vauban-examples.http` for the IntelliJ HTTP Client.

## Tests

```bash
mvn -pl cassini-examples/cassini-examples-vauban test
```

15 tests:
- `GreetingResourceTest` (3) — greetings API via CDI
- `TodoResourceTest` (7) — full CRUD via CDI
- `StaticUiTest` (5) — verifies `/`, `/style.css`, `/app.js`, 404, API+static coexistence

`ExampleServer` reuses exactly the same `VaubanApp.composeHandler()` as `Main.java`: tests and production have the same configuration.

## Why is this more complex than the other examples?

1. **Manual Chappe bootstrap** instead of `SeBootstrap` — required to serve statics at `/` (SeBootstrap does not expose the root handler).
2. **`/api` prefix** — without it, JAX-RS routes would conflict with static files.
3. **Composite handler** — classic pattern for mixing multiple responsibilities on the same HTTP server.

The details are encapsulated in `VaubanApp.java` — `Main.java` stays simple and readable.

## See also

- [`cassini-examples/README.md`](../README.md) — `BeanProvider` discovery, transport conflicts.
- [`cassini-examples-chappe`](../cassini-examples-chappe) — pure Mode A without CDI (direct comparison).
- [`cassini-cdi-vauban`](../../cassini-cdi-vauban) — Vauban adapter: `VaubanBeanProvider`, `VaubanCDIProvider`.
