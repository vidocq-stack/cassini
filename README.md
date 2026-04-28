# Cassini

Implémentation **Jakarta RESTful Web Services 4.0** standalone — REST 4.0 pur, transport HTTP via SPI, CDI optionnel.

Cassini est conçu pour être consommé dans trois modes de certification indépendants :

| Mode | Composition | Cible |
|------|-------------|-------|
| **A** | `cassini-core` + transport (`cassini-jdk-http` ou `cassini-chappe`) | REST 4.0 standalone, sans CDI |
| **B** | + `cassini-cdi` + Vauban | REST 4.0 complet, CDI via Vauban |
| **C** | Tout le stack Vidocq MPS | Core Profile 11 + MicroProfile |

## Statut TCK

**2535/2535 tests passants** — conformance Jakarta REST 4.0 / Core Profile / SE-Bootstrap.

```
[INFO] Tests run: 2670, Failures: 0, Errors: 0, Skipped: 135
[INFO] BUILD SUCCESS
```

Détails dans [`TCK.md`](TCK.md), reproduction via `./run-official-tck-restful-4.0.sh all`.

## Modules

- **`cassini-api`** — interfaces publiques + SPI HTTP (zéro dép hors `jakarta.ws.rs-api`)
- **`cassini-core`** — Invoker, ResourceScanner, MessageBodyRegistry, providers built-in (JSON-B, multipart), `RuntimeDelegate`
- **`cassini-cdi`** — intégration CDI optionnelle (`@RequestScoped` via BCE)
- **`cassini-chappe`** — adapter HTTP pour Chappe (transport de référence)
- **`cassini-jdk-http`** — adapter HTTP basé sur `com.sun.net.httpserver.HttpServer` (JDK pur)
- **`cassini-tck`** — runner Arquillian + harness officiel Jakarta REST 4.0

## SPI HTTP

Cassini ne dépend d'aucun moteur HTTP. Pour brancher un transport, implémenter dans `io.vidocq.cassini.spi.http` :

- `CassiniHttpExchange` — abstraction requête/réponse
- `CassiniHttpAdapter` — point d'entrée serveur (`dispatch(exchange) → CompletionStage<Void>`)
- `CassiniAsyncContext` — suspend/resume/timeout/callbacks
- `CassiniStreamingSink` — push chunked (SSE, StreamingOutput async)

## Quickstart (Mode A)

```java
import io.vidocq.cassini.Cassini;
import io.vidocq.cassini.jdkhttp.JdkHttpAdapter;

Cassini app = Cassini.builder()
    .register(MyResource.class)
    .build();

JdkHttpAdapter.serve(app, 8080);
```

## Roadmap

### M2h — Async non-bloquant + virtual threads (≈ 8-13 j)
- `@Suspended AsyncResponse` non-bloquant
- `CompletionStage` propagé jusqu'au transport
- Adapter Chappe sur virtual threads
- Lifecycle callbacks `addCompletionCallback` / `addConnectionCallback`
- Débloque tous les tests `@Tag("async")` actuellement préparés mais désactivés

### M2i — SSE streaming réel (≈ 1-2 j, dépend de M2h)
- Refactor `CassiniSseEventSink` pour push chunked au fil de l'eau
- Débloque les 3 challenges SSE (`sseBroadcastTest`, `closeTest` × 2)

## Build

```bash
./mvnw -ntp install
```

Pré-requis : Java 25, Maven 4.0.0-rc-5 (`.sdkmanrc` fourni).

## License

Apache License 2.0 — voir [`LICENSE`](LICENSE).
