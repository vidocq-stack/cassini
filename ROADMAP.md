# Cassini — Roadmap

Implémentation Jakarta RESTful Web Services 4.0 (JAX-RS) transport-agnostique,
zéro dépendance hors specs Jakarta. Adapters de transport pluggables (chappe,
jdk-http). DI optionnelle via SPI `BeanProvider` (adapter Vauban fourni).

> Vue produit : voir [`README.md`](README.md). Détails techniques : [`CLAUDE.md`](CLAUDE.md).

## Statut TCK Jakarta REST 4.0

| Métrique | Valeur |
|---|---|
| Profil cible | **Core Profile / SE-Bootstrap** (standalone, sans Servlet ni JAXB serveur) |
| TCK | `jakarta.ws.rs:jakarta-restful-ws-tck:4.0.1` |
| JDK | Temurin 25 |
| Tests `@Test` du TCK | **2670** |
| Tests applicables au profil | **2535** |
| **PASS** | **2535** (100 %) |
| Failures + Errors | **0** |
| Skipped | **135** (134 tags hors-profil + 6 challenges + 1 dispense interne) |

✅ **Conforme** sur le profil Core Profile / SE-Bootstrap. Détail des exclusions
et challenges officiels : voir [`TCK.md`](TCK.md).

## Phases livrées

### M1 — Fondations ✅
- API publique `cassini-api` : `CassiniHttpExchange`, `CassiniHttpAdapter`, `ResourceFactory`, `BeanProvider`
- Implémentation `cassini-core` : Invoker, ResourceScanner, MessageBodyRegistry, RuntimeDelegate
- Routage par annotations `@Path`/`@GET`/`@POST`/etc., paramètres `@PathParam`/`@QueryParam`/`@HeaderParam`/`@FormParam`/`@MatrixParam`
- Sub-resources, sub-resource locators, content negotiation `@Produces`/`@Consumes`

### M2 — Transport + adapters ✅
- Adapter `cassini-chappe` (transport de référence, utilisé pour le TCK)
- Adapter `cassini-jdk-http` (zéro dep externe, `com.sun.net.httpserver`)
- Adapter `cassini-cdi-vauban` (DI optionnelle via Vauban)

### M2a — Exception mapping + filters ✅
- `ExceptionMapper`, `ContainerRequestFilter`, `ContainerResponseFilter`
- `NameBinding` + ordre de filtres respecté

### M2b — Providers MessageBody ✅
- Built-in : `String`, `byte[]`, `InputStream`, `Reader`, `Form`, `MultivaluedMap`, `File`
- Discovery via `@Provider` + ServiceLoader pour les extensions tierces

### M2c — UriInfo + Links ✅
- `UriBuilder`, `Link`, `Link.Builder`, headers `Link:`

### M2d — Client API _(en cours — déclenché par humboldt M7c.6 le 2026-05-23)_

> **Note historique** : ce jalon avait été marqué ✅ par erreur dans une version
> antérieure de la roadmap. Le code Client API n'a jamais été implémenté ; le TCK
> Jakarta REST 4.0 passait à 2535/2535 sans grâce à l'usage de Jersey comme client
> dans `cassini-tck` (cf. `CassiniMultipartAutoDiscover.java`). L'implémentation
> commence aujourd'hui pour débloquer le TCK MP Telemetry 2.1 d'humboldt qui exige
> un provider `jakarta.ws.rs.client.ClientBuilder` sur le classpath.

- [ ] `cassini-client` (nouveau module) — backend `java.net.http.HttpClient` + virtual threads
- [ ] `Client`, `WebTarget`, `Invocation.Builder`, `Invocation` synchrones (GET/POST/PUT/DELETE)
- [ ] Filtres CLIENT (`ClientRequestFilter`/`ClientResponseFilter`) — prépare humboldt M7c.12
- [ ] Sérialisation request/response body via `MessageBodyRegistry` (réutilise les builtins de cassini-core)
- [ ] Discovery via `META-INF/services/jakarta.ws.rs.client.ClientBuilder` + JPMS `provides`
- [ ] Tests E2E avec `com.sun.net.httpserver.HttpServer` éphémère in-process
- [ ] Validation : remplacer Jersey par cassini-client dans cassini-tck (gate : 2535/2535 PASS conservé)
- [ ] Async (`InvocationCallback`, `CompletionStage`) reporté en M2d.2 si non requis par les TCK consommateurs

### M2e — Validation + Bean Validation pont ✅
- `@Valid` sur ressources, retour 400/422 avec messages

### M2f — SSE base ✅
- `Sse`, `SseEventSource`, `SseBroadcaster`, `OutboundSseEvent`
- API conforme spec, streaming réel à finaliser (M2i)

### M2g — Multipart minimal ✅
- Support `multipart/form-data` pour les ressources qui le déclarent

## Phases en cours / à venir

### M2h — Async non-bloquant + virtual threads

**Statut** : invariants préparés, tests `@Tag("async")` désactivés en attendant.

- [ ] `@Suspended AsyncResponse` propage jusqu'au transport sans block
- [ ] `CompletionStage<Response>` resource methods, callbacks lifecycle
- [ ] Validation : pas de pinning sur les virtual threads, `ScopedValue` pour le contexte requête
- [ ] Activation des `@Tag("async")` du TCK + suite custom de regression

Fichiers concernés :
- `cassini-core/src/main/java/io/vidocq/cassini/internal/Async.java`
- `cassini-core/src/main/java/io/vidocq/cassini/spi/CassiniAsyncContext.java`

### M2i — SSE streaming réel

**Dépend de M2h** (push asynchrone côté transport).

- [ ] Refactor `CassiniSseEventSink` pour push chunked au fil de l'eau (pas de
      buffering complet en mémoire)
- [ ] Heartbeat configurable (keep-alive HTTP/1.1)
- [ ] Tests : client SSE consomme events incrémentalement, deconnexion propre

### M3 — Extensions Vidocq ✅ (livré dans le runtime)
- Extension `vidocq-runtime-cassini-rest-extension` chargée via ServiceLoader
- Cf. [vidocq runtime ROADMAP](../vidocq/ROADMAP.md)

### M4 — Performance & footprint (TBD)
- [ ] Benchmarks JMH end-to-end vs RestEasy/Jersey
- [ ] Réduction allocation hot path (réutilisation contextes)
- [ ] AOT GraalVM native-image (prérequis : zéro réflexion runtime à valider)

## Backlog technique

- [ ] `cassini-migration.md` à jour avec retours d'expérience ports d'apps existantes
- [ ] Examples enrichis (`cassini-examples`) : OAuth2 resource server, file upload
      streaming, validation custom
- [ ] Évaluer support natif HTTP/2 server-side via chappe (push de ressources liées)

## Bugs

Pas de `BUG.md` dédié — les bugs ouverts sont tracés directement dans les commits
et les tests qui les couvrent. Ouvrir un `BUG.md` si une régression non triviale
apparaît.

## Conventions de tracking

- **Cette roadmap** : milestones M-x, vision moyen/long terme.
- **`TCK.md`** : statut conformité, exclusions justifiées, challenges officiels.
- **`CLAUDE.md`** : conventions code/architecture pour la session AI.
- **`cassini-migration.md`** : notes d'aide au port d'apps Jersey/RestEasy.
