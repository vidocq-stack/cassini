# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Le TCK officiel `jakarta.ws.rs:jakarta-restful-ws-tck:4.0.1` doit être installé dans le M2 local (artefact non-public)

## Commandes essentielles

```bash
# Build du reactor (sans TCK)
./mvnw -ntp install -DskipTests

# Build avec tests unitaires (cassini-core uniquement)
mvn test

# TCK — smoke test seulement
./run-official-tck-restful-4.0.sh

# TCK — suite complète (2670 tests, attendu : 2535 PASS / 135 SKIP / 0 ERR)
./run-official-tck-restful-4.0.sh all

# TCK — test ciblé
./run-official-tck-restful-4.0.sh -Dtest=NomDuTest
```

> `cassini-tck` est **hors reactor** (pom.xml en Model 4.0.0 standalone) pour contourner une incompatibilité ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0. Ne pas changer ce modèle.

## Architecture

Cassini est une implémentation Jakarta RESTful Web Services 4.0 (Core Profile / SE-Bootstrap) **transport-agnostique**.

```
cassini-api     ← SPI HTTP public (CassiniHttpExchange, CassiniHttpAdapter, ResourceFactory)
cassini-core    ← Implémentation JAX-RS (Invoker, ResourceScanner, MessageBodyRegistry, RuntimeDelegate)
cassini-cdi     ← Intégration CDI optionnelle (@RequestScoped via BCE)
cassini-chappe  ← Adapter Chappe (transport de référence, utilisé pour le TCK)
cassini-jdk-http ← Adapter JDK pur (com.sun.net.httpserver, zéro dépendance externe)
cassini-tck     ← Runner Arquillian + harness officiel Jakarta REST 4.0
```

**Flux d'une requête :** `CassiniHttpAdapter.dispatch()` → `Invoker` (core) → resource method → `CassiniHttpResponse` → transport.

**Deux modes d'instanciation des ressources :**
- Mode A : `new()` via `DefaultResourceFactory` (jdk-http, standalone)
- Mode B : CDI via `CdiResourceFactory` (cassini-cdi)

**`RuntimeDelegate`** : déclaré uniquement dans `cassini-chappe` et `cassini-jdk-http` via ServiceLoader. `cassini-core` contient `CassiniRuntimeDelegate` mais ne l'expose plus pour éviter les collisions.

## Contraintes d'architecture à ne pas violer

1. **Zéro import `fr.vidocq.chappe` dans `cassini-core`** — le découplage transport est une contrainte fondamentale (voir `cassini-migration.md`).
2. **groupId Chappe canonique** : `io.vidocq.chappe` (pas `fr.vidocq.chappe`).
3. **TCK 2535/2535 est un contrat** — toute modification de `cassini-core` doit préserver ce score ; lancer le TCK avant de commiter des changements structurels.
4. **`cassini-tck/pom.xml` reste en Model 4.0.0** — ne pas passer en 4.1.0 tant que ShrinkWrap n'est pas mis à jour.

## Conventions

- **Java modules explicites** : tous les modules ont un `module-info.java`.
- **Packages** : `io.vidocq.cassini.spi.*` = SPI public stable ; `io.vidocq.cassini.internal.*` = code interne (peut casser entre versions).
- **Maven groupId** : `io.vidocq.cassini`.

## Roadmap en cours

- **M2h** — Async non-bloquant + virtual threads : `@Suspended AsyncResponse`, propagation `CompletionStage` jusqu'au transport, lifecycle callbacks. Les invariants async sont déjà préparés dans `cassini-core/internal/Async.java` et `CassiniAsyncContext` (SPI). Les tests `@Tag("async")` sont désactivés en attendant.
- **M2i** — SSE streaming réel : refactoring `CassiniSseEventSink` pour push chunked au fil de l'eau (dépend de M2h).

## Tests unitaires dans cassini-core

```
UriTemplateTest, UriRouterBestMatchTest, MediaTypesTest, FormDecoderTest, ParamValueConverterTest
```

## Challenges TCK documentés

6 tests désactivés via `TckChallengeExclusions` avec justification dans `TCK.md` :
- 1 interprétation spec non-portable (`locatorNameTooLongAgainTest`)
- 1 environnement TCK sigtest (`signatureTest`)
- 2 multipart bloquants côté Jersey CLIENT (pas Cassini)
- 2 SSE streaming réel (hors scope jusqu'à M2i)
