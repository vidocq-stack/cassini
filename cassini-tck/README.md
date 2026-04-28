# vidocq-rest-cassini-tck-runner

Harness de conformance **Jakarta RESTful Web Services 4.0** pour l'extension
Cassini. Module Maven **volontairement hors du reactor** principal Vidocq —
cf. `vidocq-core-extensions/pom.xml`.

## Pourquoi hors reactor ?

Le TCK officiel Jakarta REST 4.0 tire transitivement **ShrinkWrap Maven
Resolver 3.3**, qui s'appuie sur `maven-resolver 1.9` et `maven-model 3.9`.
Ces versions ne savent pas parser les POMs `Model 4.1.0` du reactor Vidocq —
ShrinkWrap's `ClasspathWorkspaceReader` crashe avec
`Bad artifact coordinates ... jar:`.

La solution retenue (identique à `vidocq-servlet-chappe-tck-runner`) :
projet Maven autonome en `Model 4.0.0`, dépendances internes gelées sur la
version `0.1.0-SNAPSHOT` installée dans le repo M2 local.

## Installation des artefacts TCK

Les JAR du TCK officiel ne sont pas sur Maven Central. À récupérer une fois
depuis l'Eclipse Foundation puis à installer en local :

```bash
curl -Lo /tmp/restful-ws-tck.zip \
  https://download.eclipse.org/jakartaee/restful-ws/4.0/jakarta-restful-ws-tck-4.0.0.zip
unzip /tmp/restful-ws-tck.zip -d /tmp/restful-ws-tck

mvn install:install-file \
  -Dfile=/tmp/restful-ws-tck/jakarta-restful-ws-tck/lib/jakarta-restful-ws-tck-4.0.0.jar \
  -DgroupId=jakarta.tck -DartifactId=jakarta-restful-ws-tck -Dversion=4.0.0 -Dpackaging=jar
```

(Si la structure du zip diffère, adapter les chemins.)

## Lancement

Depuis la racine du projet Vidocq :

```bash
./run-official-tck-restful-4.0.sh                     # smoke test
./run-official-tck-restful-4.0.sh all                 # suite complète
./run-official-tck-restful-4.0.sh -Dtest=SomeTests    # classe ciblée
```

Le script :
1. Installe les modules Vidocq en M2 local (`mvn install` du reactor).
2. Se place dans `vidocq-core-extensions/vidocq-rest-cassini-tck-runner`.
3. Lance `mvn -Ptck-official verify` (ou `-Dtest=` si ciblé).

## Architecture du harness

| Composant | Rôle |
|---|---|
| `CassiniTestHarness` | Monte Chappe + Cassini sur un port éphémère à partir d'une liste de classes `@Path`. |
| `VidocqCassiniDeployableContainer` | Adaptateur Arquillian : scanne `WEB-INF/classes/` du WAR TCK, extrait les classes `@Path`, les charge dans un harness. |
| `VidocqContainerExtension` | Enregistre le container via le SPI `LoadableExtension`. |
| `VidocqContainerConfiguration` | Accepte l'hôte via `arquillian.xml`. |
| `arquillian.xml` | Paramétrage du container (hôte par défaut `127.0.0.1`). |

## Statut

- Smoke test `CassiniHarnessSmokeTest` : valide que le harness démarre et
  répond à une requête `GET /ping`.
- TCK officiel : **scaffolding en place** (profile `tck-official`). L'exécution
  réelle et l'itération jalon par jalon démarrent dès que les artefacts TCK
  sont installés localement.
