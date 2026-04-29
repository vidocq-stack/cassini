# cassini-examples-chappe

**Mode A pur** — Cassini + transport Chappe, sans CDI.

C'est le cas d'usage le plus simple : une application JAX-RS standalone bootstrappée via `SeBootstrap` standard, où chaque ressource est instanciée via son constructeur sans argument à chaque requête.

## Lancer

```bash
mvn -pl cassini-examples/cassini-examples-chappe \
    exec:java -Dexec.mainClass=io.vidocq.cassini.examples.chappe.Main
```

ou directement `Main.java` depuis IntelliJ.

Le serveur démarre sur `http://localhost:8080`.

## Endpoints

| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/greetings` | "Hello, World!" (text/plain) |
| `GET` | `/greetings/{name}` | "Hello, {name}!" |
| `GET` | `/todos` | Liste des todos (JSON) |
| `POST` | `/todos` | Crée un todo, retourne 201 + JSON |
| `GET` | `/todos/{id}` | Récupère un todo, 404 si absent |
| `PUT` | `/todos/{id}` | Met à jour un todo |
| `DELETE` | `/todos/{id}` | Supprime un todo, 204 |

Le fichier `src/test/resources/http/chappe-examples.http` contient toutes les requêtes pour tester via le HTTP Client d'IntelliJ.

## Comment ça marche

### Bootstrap

`Main.java` utilise l'API publique standard `SeBootstrap` :

```java
SeBootstrap.start(new ExamplesApp(),
    SeBootstrap.Configuration.builder()
        .host("0.0.0.0").port(8080).build());
```

Sous le capot :

1. **`SeBootstrap.start()`** appelle `RuntimeDelegate.getInstance().bootstrap(app, config)`.
2. **`RuntimeDelegate.getInstance()`** utilise `ServiceLoader.load(RuntimeDelegate.class)` qui trouve `ChappeRuntimeDelegate` (déclaré dans `cassini-chappe/module-info.java` via `provides RuntimeDelegate with ChappeRuntimeDelegate`).
3. **`ChappeRuntimeDelegate.bootstrap()`** appelle `CassiniStack.builder().application(app).build()` pour assembler la stack JAX-RS, puis crée un `Server` Chappe et y branche un `ChappeHttpAdapter` pointant sur la stack.
4. **`ChappeHttpAdapter`** convertit les `Request`/`Response` Chappe en `CassiniHttpExchange` et délègue à `CassiniHttpAdapter.dispatch()`.

### Pas de CDI

Aucun `BeanProvider` n'est sur le classpath, donc l'auto-discovery dans `CassiniStack.builder()` ne trouve rien. Le résolveur tombe en mode "Mode A" : `clazz.getDeclaredConstructor().newInstance()` à chaque requête. Les ressources doivent avoir un constructeur public sans argument.

### Stockage Todo

`TodoResource` utilise une `ConcurrentHashMap` statique — les données vivent dans la JVM, partagées entre toutes les requêtes. Pour un vrai cas d'usage il faudrait un service injecté (voir `cassini-examples-vauban`).

## Tests

```bash
mvn -pl cassini-examples/cassini-examples-chappe test
```

10 tests : `GreetingResourceTest` (3) + `TodoResourceTest` (7).

`ExampleServer` démarre Cassini sur un port aléatoire pour chaque suite de tests, `HttpClient` JDK envoie de vraies requêtes HTTP.

## Voir aussi

- [`cassini-examples-jdkhttp`](../cassini-examples-jdkhttp) — même chose mais sans Chappe (transport JDK natif).
- [`cassini-examples-vauban`](../cassini-examples-vauban) — version avec CDI Vauban + UI statique.
- [`cassini-examples/README.md`](../README.md) — vue d'ensemble et mécanisme de découverte des transports.
