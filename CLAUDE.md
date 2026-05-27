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
cassini-api          ← SPI HTTP public + SPI codegen stable (ResourceAdapter, InjectionSupport - spi.gen)
cassini-core         ← Implémentation JAX-RS + RuntimeAdapterGenerator (Class-File API) + AdapterRegistry
cassini-processor    ← APT (javax.annotation.processing) — génère CassiniAdapter à la compilation
cassini-maven-plugin ← Maven plugin — pré-génère CassiniAdapter pour archives externes (dep JARs)
cassini-cdi-vauban   ← Adapter CDI Vauban (BeanProvider + BCE @RequestScoped, optionnel)
cassini-chappe       ← Adapter Chappe (transport de référence, utilisé pour le TCK)
cassini-jdk-http     ← Adapter JDK pur (com.sun.net.httpserver, zéro dépendance externe)
cassini-tck          ← Runner Arquillian + harness officiel Jakarta REST 4.0
```

**Flux d'une requête :** `CassiniHttpAdapter.dispatch()` → `Invoker` (core) → resource method → `CassiniHttpResponse` → transport.

### Architecture codegen M4

Trois niveaux de génération d'adapters (`<Class>$$CassiniAdapter`), du plus préféré au fallback :

1. **APT compiler-time (`cassini-processor`)** — classes sources du build courant. AOT-safe.
2. **Maven plugin build-time (`cassini-maven-plugin:generate`, `process-classes`)** — archives
   externes (dep JARs). Appelle `RuntimeAdapterGenerator.toBytecode(cls)` et écrit les `.class`
   sur disque. AOT-safe.
3. **Générateur runtime (`RuntimeAdapterGenerator.generate`)** — fallback JVM-only. Non compatible AOT.

`AdapterRegistry.lookup` essaie `Class.forName(<class>$$CassiniAdapter)` en premier (chemin
APT/plugin), puis le générateur runtime, puis retourne le SENTINEL (fallback réflexif).

**Règle JPMS named-module (plugin)** : les adapters vivent dans le package de la ressource.
Classpath JARs → écriture dans `target/classes`. JPMS named-module → fail-build (option
`repackageModularDependencies=true` pour repackager le JAR).

**Réflexion résiduelle documentée (dérogation assumée)** :
- `ResourceScanner` au démarrage (scan annotations JAX-RS, une seule fois).
- Instanciation des ressources et beans (`getDeclaredConstructor().newInstance()`) — one-time, hors hot-path.
- `InjectionSupportImpl.beanParam` : instanciation toujours réflective ; injection des champs du bean via son adapter généré (P4), ou fallback réflexif si l'adapter ne peut être généré (module fermé, superclasse privée).
- Fallback runtime pour locators dynamiques (`Object`) et classes dans modules fermés (superclasse privée inaccessible au `privateLookupIn`) — SENTINEL + `FieldInjector.inject` en filet.
- Injection `@Context` dans les providers singletons (filtres, MBW/MBR) via `FieldInjector.inject` — hors du hot-path ressource.
- `ParamValueConverter` et coercition de types : réflexion structurelle au premier appel par type (pas par requête).

**Deux modes d'instanciation des ressources :**
- Mode A : `new()` via `DefaultResourceFactory` (jdk-http, standalone)
- Mode B : DI via SPI publique `BeanProvider` (`cassini-cdi-vauban` ou tout autre adapter ServiceLoader). Aucun import `jakarta.cdi` dans `cassini-api`/`cassini-core` — découplage strict.

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

## Tests unitaires

**cassini-core** : `UriTemplateTest`, `UriRouterBestMatchTest`, `MediaTypesTest`, `FormDecoderTest`,
`ParamValueConverterTest`, `RuntimeAdapterGeneratorTest` (inclut toBytecode P3),
`AdapterRegistrySeamTest`.

**cassini-processor** : `CassiniResourceProcessorTest`.

**cassini-maven-plugin** : `GenerateAdaptersMojoTest` (toBytecode round-trip, JAR named-module detection).

## Challenges TCK documentés

6 tests désactivés via `TckChallengeExclusions` avec justification dans `TCK.md` :
- 1 interprétation spec non-portable (`locatorNameTooLongAgainTest`)
- 1 environnement TCK sigtest (`signatureTest`)
- 2 multipart bloquants côté Jersey CLIENT (pas Cassini)
- 2 SSE streaming réel (hors scope jusqu'à M2i)
