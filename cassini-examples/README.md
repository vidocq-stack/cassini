# Cassini — Exemples

Trois exemples illustrent les manières d'utiliser Cassini selon le transport et l'intégration souhaités.

| Module | Transport | CDI | Bootstrap |
|---|---|---|---|
| `cassini-examples-chappe` | Chappe (HTTP/1.1 + HTTP/2) | non | `SeBootstrap.start()` |
| `cassini-examples-jdkhttp` | JDK natif (`com.sun.net.httpserver`) | non | `CassiniStack.builder()` ou `SeBootstrap.start()` |
| `cassini-examples-vauban` | Chappe + CDI Vauban | oui (`@ApplicationScoped` + `@Inject`) | `VaubanContainer` + `SeBootstrap.start()` (auto-discovery `BeanProvider`) |

Tous trois exposent les mêmes ressources pour comparaison directe :
- `GET /greetings` → `"Hello, World!"`
- `GET /greetings/{name}` → `"Hello, {name}!"`
- `GET|POST|PUT|DELETE /todos[/{id}]` → CRUD JSON

## Comment lancer

Chaque module a un `Main.java` lançable depuis IntelliJ ou via :

```bash
mvn -pl cassini-examples/cassini-examples-{chappe,jdkhttp,vauban} \
    exec:java -Dexec.mainClass=io.vidocq.cassini.examples.{chappe,jdkhttp,vauban}.Main
```

Les fichiers `.http` dans `src/test/resources/http/` permettent de tester via le HTTP Client d'IntelliJ.

---

## Mécanisme de découverte du transport — `SeBootstrap`

`jakarta.ws.rs.SeBootstrap.start(app, config)` utilise le ServiceLoader JPMS standard pour trouver une implémentation de `jakarta.ws.rs.ext.RuntimeDelegate` :

```
SeBootstrap.start(app, config)
    ↓
RuntimeDelegate.getInstance()
    ↓
ServiceLoader.load(RuntimeDelegate.class)   ← cherche les modules avec
                                                provides RuntimeDelegate with ...
    ↓
Premier provider trouvé → utilisé
```

### Où c'est déclaré dans Cassini

| Module | Provider | Déclaration |
|---|---|---|
| `cassini-chappe` | `ChappeRuntimeDelegate` | `module-info.java` : `provides jakarta.ws.rs.ext.RuntimeDelegate with ChappeRuntimeDelegate;` |
| `cassini-jdk-http` | `JdkHttpRuntimeDelegate` | `module-info.java` : `provides jakarta.ws.rs.ext.RuntimeDelegate with JdkHttpRuntimeDelegate;` |
| `cassini-core` | (rien) | Le `CassiniRuntimeDelegate` interne n'est **pas** exposé via ServiceLoader pour éviter les conflits — c'est le rôle des transports. |

### Conflit si plusieurs transports sur le classpath

Si `cassini-chappe` ET `cassini-jdk-http` sont tous deux dans le module graph, le ServiceLoader retourne le **premier** trouvé — comportement non-déterministe.

**Solution** : forcer le choix via la system property standard JAX-RS :

```bash
java -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.chappe.ChappeRuntimeDelegate ...
```

ou l'inverse pour JDK :

```bash
java -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.jdkhttp.JdkHttpRuntimeDelegate ...
```

Dans la pratique, une application n'utilise qu'**un seul** transport : on n'inclut qu'un des deux artefacts (`cassini-chappe` OU `cassini-jdk-http`) dans le `pom.xml`.

---

## Découverte du `BeanProvider` (intégration DI)

Cassini expose une SPI publique `io.vidocq.cassini.spi.bean.BeanProvider` permettant à un container DI (CDI Vauban, Weld, OpenWebBeans, ou tout autre mécanisme) de fournir des instances managées de ressources et providers JAX-RS, **sans que `cassini-api` ni `cassini-core` ne dépendent de `jakarta.cdi`**.

```
CassiniStack.builder()
    ↓
ServiceLoader.load(BeanProvider.Factory.class)
    ↓ (max priority())
Factory.create() → BeanProvider
    ↓ utilisé pour instancier @Path / @Provider
```

| Adapter | Container | Priorité |
|---|---|---|
| `cassini-cdi-vauban` | Vauban CDI (`io.vidocq.vauban`) | 100 |
| (futur) `cassini-cdi-weld` | Weld | — |
| (futur) `cassini-cdi-owb` | OpenWebBeans | — |

Quand un `BeanProvider` est actif :

- `BeanProvider.getResourceClasses()` est fusionné avec `Application.getClasses()` et `getSingletons()` — l'utilisateur peut donc passer `new Application() {}` vide.
- Le `resolver` interne tente d'abord `beanProvider.getBean(cls)`. Si la classe n'est pas managée (`IllegalArgumentException`), fallback sur `ResourceFactory` puis sur `newInstance()`.
- L'utilisateur peut désactiver l'auto-discovery via `CassiniStack.builder().beanProvider(null)`.

---

## Bootstrap manuel — `CassiniStack`

Pour les cas où on ne veut **pas** passer par `SeBootstrap` (par exemple pour configurer finement le transport, intégrer dans un framework existant, ou dans un pipeline de tests), on bootstrap manuellement :

```java
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;

var stack = CassiniStack.builder()
    .application(new MyApplication())
    .beanProvider(myBeanProvider)               // optionnel — sinon auto-discovery
    .provider(new MyExceptionMapper())          // optionnel
    .build();

CassiniHttpAdapter adapter = stack.adapter();
// adapter.dispatch(exchange) — à appeler depuis votre transport
```

`CassiniStack.builder()` est disponible dans `cassini-api` (la SPI publique). Cette interface est implémentée par `cassini-core` via ServiceLoader (`provides CassiniStack.BuilderFactory`).

### Cas d'usage par exemple

#### `cassini-examples-chappe` — `SeBootstrap` standard

```java
SeBootstrap.start(new ExamplesApp(),
    SeBootstrap.Configuration.builder()
        .host("0.0.0.0").port(8080).build());
```

Le `RuntimeDelegate` Chappe est trouvé automatiquement. Pas de bootstrap manuel.

#### `cassini-examples-jdkhttp` — `CassiniStack` direct

```java
var stack = CassiniStack.builder().application(new ExamplesApp()).build();
var server = new JdkHttpAdapter(stack.adapter()).serve(8080);
```

On bypass `SeBootstrap` pour démontrer l'API bas-niveau. Mais comme `JdkHttpRuntimeDelegate` est aussi enregistré, on **pourrait** utiliser `SeBootstrap.start()` à la place.

#### `cassini-examples-vauban` — CDI + `SeBootstrap`

```java
// 1. Démarrer Vauban CDI (s'enregistre comme CDI.current() via VaubanCDIProvider)
var container = VaubanContainer.builder()
        .addBeanClass(TodoService.class)
        .addBeanClass(TodoResource.class)
        .build();

// 2. SeBootstrap — l'Application est vide. Cassini découvre le BeanProvider
//    Vauban via ServiceLoader, scanne les classes @Path/@Provider managées
//    par le container et les instancie via @Inject résolu.
SeBootstrap.start(new Application() {}, config);
```

Aucune liste manuelle de singletons : `VaubanBeanProvider.getResourceClasses()` itère le `BeanManager` et expose toutes les classes annotées `@Path`/`@Provider` au stack. Le `resolver` interne appelle `container.select()` à chaque dispatch (respecte `@RequestScoped` etc.).

---

## Architecture des dépendances

```
              ┌─ cassini-api (SPI publique)
              │     ├── CassiniHttpExchange, CassiniHttpAdapter
              │     ├── CassiniStack, ResourceFactory
              │     └── (zéro dépendance hors jakarta.ws.rs-api)
              │
              ├─ cassini-core (implémentation, fermée)
              │     ├── Invoker, UriRouter, ResourceScanner...
              │     ├── CassiniRuntimeDelegate (base pour transports)
              │     └── exports internal.runtime to {tck, jdkhttp}
              │
   exemple ───┼─ cassini-chappe ──→ requires cassini-api + cassini-core (ServiceLoader)
              │     provides RuntimeDelegate with ChappeRuntimeDelegate
              │
   exemple ───┼─ cassini-jdk-http ──→ requires cassini-api + cassini-core
              │     provides RuntimeDelegate with JdkHttpRuntimeDelegate
              │
   exemple ───┴─ cassini-cdi-vauban ──→ requires cassini-api + io.vidocq.vauban.core
                    provides BeanProvider.Factory with VaubanBeanProviderFactory
```

**Règle d'or** : `cassini-chappe`, `cassini-jdk-http` et `cassini-cdi-vauban` n'importent **aucun package interne** de `cassini-core`. Ils utilisent uniquement la SPI publique (`cassini-api` + `CassiniStack` + `BeanProvider`). Le `requires cassini-core` n'est même plus nécessaire pour `cassini-cdi-vauban` qui ne parle qu'à `BeanProvider` (SPI publique).

Cette discipline permet à n'importe quel écosystème (Vidocq, Weld, Quarkus, autre) d'écrire son propre transport ou intégration DI **sans accéder aux internes de Cassini**.

---

## Tests automatisés

Chaque exemple a une classe `ExampleServer` (AutoCloseable) utilisée par les tests JUnit 5 :

```java
@BeforeAll
static void start() throws Exception { server = new ExampleServer(); }

@AfterAll
static void close() throws Exception { server.close(); }

@Test
void listEmpty() throws Exception {
    var resp = http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
}
```

Le serveur démarre sur un port aléatoire (`ServerSocket(0)`), assurant l'isolation entre tests parallèles.
