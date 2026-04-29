# HOWTO — Cassini avec Claude Code

Documentation rapide pour les futurs sessions Claude Code sur ce projet.

## Contexte

Cassini est l'**implémentation Jakarta REST 4.0 standalone** extraite de
`vidocq-mps` en avril 2026. Ses 6 modules :

| Module | Rôle |
|--------|------|
| `cassini-api` | SPI HTTP (CassiniHttpExchange/Adapter/AsyncContext/StreamingSink + ResourceFactory + BeanProvider) |
| `cassini-core` | Moteur REST 4.0 (Invoker, ResourceScanner, MessageBodyRegistry, providers built-in) |
| `cassini-cdi-vauban` | Adapter Vauban CDI (Mode B) — VaubanBeanProvider via SPI BeanProvider + CassiniScopeExtension (BCE) |
| `cassini-chappe` | Adapter HTTP Chappe (transport de référence, utilisé par le TCK) |
| `cassini-jdk-http` | Adapter HTTP JDK natif (Mode A pur, zéro dép externe) |
| `cassini-tck` | Runner Arquillian + harness officiel Jakarta REST 4.0 |

## Architecture

- **`cassini-core`** : zéro dépendance à Chappe, code transport-agnostique. Branchable
  via le SPI HTTP de `cassini-api`.
- **CDI provided** : `cassini-core` accepte CDI en `<scope>provided</scope>` —
  `Invoker.forBeanManager(bm)` si l'utilisateur en fournit un.
- **Découplage transport** : Tous les usages de Chappe sont confinés à `cassini-chappe`
  (`ChappeHttpAdapter` + `ChappeHttpExchange` + `ChappeRuntimeDelegate`).

## Commandes essentielles

### Build complet du reactor
```bash
mvn install -DskipTests
```

Note : `cassini-tck` est volontairement **hors reactor** (Model 4.0.0 standalone)
pour contourner ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0.

### TCK
```bash
./run-official-tck-restful-4.0.sh           # smoke (CassiniHarnessSmokeTest)
./run-official-tck-restful-4.0.sh all       # 2670 tests, attendu : 2535 PASS / 135 SKIP / 0 ERR
./run-official-tck-restful-4.0.sh -Dtest=X  # ciblé
```

Pré-requis : `jakarta.ws.rs:jakarta-restful-ws-tck:4.0.1` installé en M2 local
(non-public — TCK officiel Jakarta).

### Tests unitaires
```bash
mvn test
```

## Conventions

- **Java 25** + **Maven 4.0.0-rc-5** (cf. `.sdkmanrc`)
- **Java modules explicites** : tous les modules ont un `module-info.java`
- **Packages** :
  - `io.vidocq.cassini.spi.*` — public SPI (stabilité sémantique)
  - `io.vidocq.cassini.internal.*` — interne (peut casser entre versions)
- **groupId Maven** : `io.vidocq.cassini` (cohérent `io.vidocq.chappe`, `io.vidocq.vauban`)
- **License** : Apache 2.0
- **License headers** : pas obligatoires en MVP

## Points d'attention pour Claude Code

1. **Ne jamais réintroduire d'import `fr.vidocq.chappe`** dans `cassini-core` — le
   découplage est une contrainte d'architecture (cf. `cassini-migration.md` §4).

2. **Préserver les invariants async (M2h)** : `Invoker.invoke()` retourne
   `CassiniHttpResponse` synchrone aujourd'hui ; M2h propagera `CompletionStage`
   sans bloquer (`awaitBlocking()` à isoler).

3. **TCK 2535/2535 est un contrat** : toute modification de `cassini-core` doit
   préserver ce score (run avant commit pour les changements importants).

4. **Module-info `provides RuntimeDelegate`** : seul `cassini-chappe` (et plus tard
   `cassini-jdk-http`) déclare ce service. `cassini-core` ne l'expose plus pour
   éviter les collisions ServiceLoader.

5. **Le `cassini-tck/pom.xml` est en Model 4.0.0** — ne pas le passer en 4.1.0
   tant que ShrinkWrap n'est pas mis à jour.

6. **MEMO Q3** : `groupId` Chappe est canonique `io.vidocq.chappe` (pas `fr.vidocq.chappe`).

## Roadmap

Voir [`README.md`](README.md) pour M2h (async + virtual threads) et M2i (SSE streaming).
