# M2h — Async non-bloquant + virtual threads

> Document de préparation pour le refactor M2h. Le code actuel (extraction
> 0.1.0-SNAPSHOT) bloque sur `CompletionStage.get()` mais isole les invariants
> à propager pour faciliter la migration.

## Objectif

Faire passer Cassini d'une exécution synchrone (1 thread par requête, blocage
sur `@Suspended AsyncResponse` et `CompletionStage`) à une exécution
non-bloquante propagée jusqu'au transport, exécutée sur des virtual threads.

## Estimation

**8-13 jours** de développement (refactor moteur + tests TCK async).

## Invariants déjà préparés

### 1. SPI HTTP retourne `CompletionStage<Void>`

[`CassiniHttpAdapter`](cassini-api/src/main/java/io/vidocq/cassini/spi/http/CassiniHttpAdapter.java) :
```java
CompletionStage<Void> dispatch(CassiniHttpExchange exchange);
```

Aujourd'hui les implémentations (`ChappeHttpAdapter`, `JdkHttpAdapter`)
exécutent synchrone et retournent `CompletableFuture.completedFuture(null)`.
M2h propagera le stage réel.

### 2. Helper `awaitBlocking` isolé

[`io.vidocq.cassini.internal.Async`](cassini-core/src/main/java/io/vidocq/cassini/internal/Async.java)
centralise le blocage. **TODO(M2h)** : remplacer chaque appel
`Async.awaitBlocking(stage)` par une propagation non-bloquante.

Toutes les autres occurrences de `.toCompletableFuture().get()` sont à
proscrire — utiliser `Async.awaitBlocking()` pour faciliter le grep M2h.

### 3. ThreadLocals annotés `TODO(M2h)`

| Fichier | ThreadLocal | Migration cible |
|---------|-------------|-----------------|
| `Invoker.java` | `CURRENT_MATCH`, `CURRENT_REQUEST`, `CURRENT_MATCHED_RESOURCES` | `ScopedValue` ou attribut `CassiniHttpExchange` |
| `CassiniRequest.java` | `PENDING_VARY` | attribut `CassiniHttpExchange` |
| `CassiniSecurityContext.java` | `CURRENT_AUTH` | attribut `CassiniHttpExchange` |
| `FieldInjector.java` | `FORM_CACHE`, `FORM_CACHE_ENCODED`, `BODY_CACHE` | attribut `CassiniHttpExchange` |
| `ParamExtractor.java` | `PROVIDERS`, etc. | `ScopedValue` |
| `ExceptionMapperRegistry.java` | `MAPPING` (re-entry guard) | thread-local OK (re-entry strictement single-frame) |
| `CassiniResponseBuilder.java` | `BASE_URI` | `ScopedValue` |

Les ThreadLocals **cassent en virtual-thread async** : si l'Invoker yield (par
exemple sur `await` d'un `CompletionStage`), la reprise du thread peut se
faire sur un autre carrier — les TL sont alors invisibles. Le refactor M2h
doit les migrer vers `ScopedValue` ou attacher au `CassiniHttpExchange`.

### 4. Tags JUnit 5 préparés

`cassini-tck/pom.xml` profil `tck-official` :
```xml
<excludedGroups>servlet,xml_binding</excludedGroups>
```

**TODO(M2h)** : retirer les exclusions `async` / `sse-streaming` quand le
refactor sera fait. Les tests sont déjà tagués dans le TCK Jakarta —
`@Tag("async")` est appliqué par les tests TCK eux-mêmes pour les méthodes
exigeant async non-bloquant.

## Sites de blocage à traiter

| Fichier:ligne | Code | Cible M2h |
|---------------|------|-----------|
| `Invoker.java:413` | `cs.toCompletableFuture().get()` (CompletionStage retourné par méthode resource) | propager dans `dispatch → CompletionStage<Void>` |
| `CassiniSseEventSink` | bufférisation puis émission en bloc | refactor M2i — push chunked au fil de l'eau via `CassiniStreamingSink` |

## Tests M2h actuellement skippés

Le TCK 4.0 expose `@Tag("async")` sur ~10 tests AsyncResponse + sur les
tests SSE streaming. Ils sont actuellement passants par accident (mode
bloquant fonctionne pour les cas simples) ou désactivés via `excludedGroups`.

Après M2h, le score visé : **2535/2535 + ~10 tests async = 2545/2545**.

## Lien avec M2i (SSE streaming)

M2i dépend de M2h. Le refactor SSE (`CassiniSseEventSink`) ne peut pas
écrire chunked au fil de l'eau tant que l'Invoker bloque jusqu'à la fin de
la méthode resource. Une fois M2h fait, M2i devient ~1-2 jours.
