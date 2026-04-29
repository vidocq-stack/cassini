# cassini-examples-jdkhttp

**Mode A pur** — Cassini + transport JDK natif (`com.sun.net.httpserver`), sans dépendance externe.

Cas d'usage : application JAX-RS embarquée minimale (CLI tool, microservice, container léger). Le seul transport HTTP est celui fourni par le JDK lui-même — zéro dépendance Maven hors `cassini-*` et `jakarta.ws.rs-api`.

## Lancer

```bash
mvn -pl cassini-examples/cassini-examples-jdkhttp \
    exec:java -Dexec.mainClass=io.vidocq.cassini.examples.jdkhttp.Main
```

ou `Main.java` depuis IntelliJ. Le serveur démarre sur `http://localhost:8080`.

## Endpoints

Identiques à `cassini-examples-chappe` : `/greetings`, `/greetings/{name}`, CRUD `/todos`.

Voir `src/test/resources/http/jdkhttp-examples.http` pour les requêtes IntelliJ.

## Comment ça marche

### Bootstrap manuel via `CassiniStack`

Contrairement à l'exemple Chappe, **on ne passe pas par `SeBootstrap`**. À la place, on bootstrap manuellement via la SPI `CassiniStack` :

```java
var stack = CassiniStack.builder()
        .application(new ExamplesApp())
        .build();

var server = new JdkHttpAdapter(stack.adapter()).serve(8080);
```

C'est l'API "bas-niveau" qui démontre comment intégrer Cassini dans n'importe quel transport tiers : on récupère un `CassiniHttpAdapter`, on le branche sur le transport.

### Sous le capot — `JdkHttpAdapter`

1. **`JdkHttpAdapter.serve(port)`** crée un `HttpServer` JDK avec un executor virtual-thread (un VT par requête).
2. Pour chaque requête entrante, le `HttpHandler` :
   - Construit un `JdkHttpExchange` (impl de `CassiniHttpExchange`) à partir du `com.sun.net.httpserver.HttpExchange`.
   - Appelle `engine.dispatch(exchange)` (`CassiniHttpAdapter` produit par `CassiniStack`).
   - Lit la réponse depuis l'exchange (`collectedStatus`, `collectedHeaders`, `collectedBody`).
   - Écrit dans le `HttpExchange` JDK via `sendResponseHeaders()` + `responseBody().write()`.

### Et `SeBootstrap` ?

`cassini-jdk-http` fournit aussi un `JdkHttpRuntimeDelegate` via ServiceLoader, donc `SeBootstrap.start()` fonctionnerait aussi. Mais l'exemple démontre l'API directe pour montrer le découplage.

**Attention conflit** : si `cassini-chappe` ET `cassini-jdk-http` sont tous deux sur le module path, `RuntimeDelegate.getInstance()` retourne le premier trouvé (non-déterministe). Forcer le choix via :

```bash
java -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.jdkhttp.JdkHttpRuntimeDelegate ...
```

En pratique, une application n'inclut qu'**un seul** transport dans son `pom.xml`.

### Comparaison Chappe vs JDK natif

| Aspect | Chappe | JDK natif |
|---|---|---|
| HTTP/1.1 keep-alive | ✅ | ✅ |
| HTTP/2 | ✅ | ❌ |
| Streaming chunked | ✅ via `Body.streaming(InputStream)` | ✅ via `sendResponseHeaders(0)` |
| Performance | optimisée (zero-copy file, pool buffers) | basique (JDK stock) |
| Dépendance | `chappe-core` (~xx KB) | aucune (incluse dans le JDK) |
| Use-case | applications haute perf | tools, embedded, distroless |

## Tests

```bash
mvn -pl cassini-examples/cassini-examples-jdkhttp test
```

10 tests, identiques structurellement à ceux de Chappe — preuve que l'abstraction fonctionne.

## Voir aussi

- [`cassini-examples/README.md`](../README.md) — vue d'ensemble et mécanisme `RuntimeDelegate`.
- [`cassini-examples-chappe`](../cassini-examples-chappe) — version avec transport Chappe.
- [`cassini-examples-vauban`](../cassini-examples-vauban) — Mode B (CDI) + UI statique.
