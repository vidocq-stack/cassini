# cassini-examples-vauban

**Mode B (CDI)** — Cassini + Vauban CDI + UI HTML/CSS/JS statique servie par Chappe.

Démo end-to-end : `http://localhost:8080/` affiche une vraie page web qui consomme l'API REST sur `/api/*`. Les ressources JAX-RS sont des beans CDI gérés par Vauban, avec injection (`@Inject TodoService`) résolue automatiquement.

## Lancer

```bash
mvn -pl cassini-examples/cassini-examples-vauban \
    exec:java -Dexec.mainClass=io.vidocq.cassini.examples.vauban.Main
```

ou `Main.java` depuis IntelliJ. Ouvrir `http://localhost:8080/` dans le navigateur.

```
┌──────────────────────────────────────────────┐
│ Cassini + Vauban CDI — démarré sur port 8080 │
├──────────────────────────────────────────────┤
│  UI  → http://localhost:8080/                │
│  API → http://localhost:8080/api/todos       │
│        http://localhost:8080/api/greetings   │
└──────────────────────────────────────────────┘
```

## Architecture

```
                         Chappe Server :8080
                                │
                                ▼
                    VaubanApp.composeHandler()
                         (handler composite)
                                │
              ┌─────────────────┴──────────────────┐
              │                                    │
        path = /api/*                       path = autre
              │                                    │
              ▼                                    ▼
     strip /api + dispatch              staticHandler()
              │                                    │
              ▼                                    ▼
     ChappeHttpAdapter             classpath:/static/
              │                       (index.html, style.css, app.js)
              ▼
       CassiniHttpAdapter
              │
              ▼
       Invoker (Cassini)
              │
              ▼
   resolver = beanProvider.getBean(cls)
              │
              ▼
   VaubanContainer.select(TodoResource.class)
              │
              ▼
   instance avec @Inject TodoService résolu
```

## Comment ça marche

### 1. Bootstrap Vauban CDI

```java
var container = VaubanContainer.builder()
        .addBeanClass(TodoService.class)
        .addBeanClass(GreetingResource.class)
        .addBeanClass(TodoResource.class)
        .build();
```

`VaubanContainer.build()` enregistre l'instance comme `CDI.current()` (via `VaubanCDIProvider` ServiceLoader). Toutes les classes sont scannées, leurs annotations CDI traitées (`@ApplicationScoped`, `@Inject`, etc.).

> **Pourquoi pas `scanLocal()` ?** Vauban propose un scan auto par package, mais il s'appuie sur `ClassLoader.getResources(packagePath)` qui ne retourne pas les répertoires des **named modules** JPMS — uniquement ceux du classpath (unnamed modules). Comme cassini-examples-vauban est un module nommé, on déclare les beans explicitement pour rester compatible avec les deux modes de lancement.

### 2. Auto-discovery du `BeanProvider`

`CassiniStack.builder()` (appelé par `VaubanApp.composeHandler()`) fait `ServiceLoader.load(BeanProvider.Factory.class)` et trouve **`VaubanBeanProviderFactory`** (priorité 100, déclaré dans `cassini-cdi-vauban/module-info.java`). La factory récupère `VaubanContainer.current()` et l'injecte dans la stack.

À partir de là, le résolveur interne devient :
```java
clazz -> beanProvider.getBean(clazz)  // → container.select(clazz) avec @Inject résolu
```

L'`Application` JAX-RS peut être **vide** (`new Application() {}`) : `BeanProvider.getResourceClasses()` itère le `BeanManager` Vauban et expose toutes les classes annotées `@Path`/`@Provider` à Cassini.

### 3. Handler composite Chappe

`Main.java` ne passe pas par `SeBootstrap` car on veut mixer statique + API REST sur le même port. À la place :

```java
var server = Server.builder()
        .host("0.0.0.0").port(8080)
        .handler(VaubanApp.composeHandler())
        .build();
```

`VaubanApp.composeHandler()` retourne un `Handler` Chappe qui dispatche selon le path :

```java
return req -> {
    if (req.path().startsWith("/api")) {
        // strip /api et délègue à ChappeHttpAdapter (qui pointe sur CassiniStack)
        return cassiniHandler.handle(stripContext(req, "/api", ...));
    }
    return staticHandler.handle(req);  // sert classpath:/static/...
};
```

### 4. Service du statique — `StaticFileHandler` Chappe

Chappe fournit nativement un `StaticFileHandler` avec support classpath, fallback chain, cache mémoire :

```java
StaticFileHandler.builder()
        .addClasspath("static")    // résolu depuis classpath:/static/
        .cacheInMemory(true)       // cache en RAM pour les ressources < 64 Ko
        .build();
```

> **Petit ajustement nécessaire** : le composite handler réécrit `/` en `/index.html` avant de passer la requête au `StaticFileHandler`. En mode classpath, `getResource("static/")` retourne l'URL du directory non-null, ce qui empêche le fallback `indexFile` interne — la réécriture côté composite contourne ça.

### 5. UI client

`src/main/resources/static/` contient :

| Fichier | Rôle |
|---|---|
| `index.html` | Structure page : header, greeting, form, liste, footer |
| `style.css` | Dark theme moderne (gradient, glow accent, animations subtiles) |
| `app.js` | Fetch API : GET `/api/todos`, POST/PUT/DELETE, render dynamique |

Le JS appelle :
- `GET /api/greetings/Vauban` au load → affiche le message dans le header
- `GET /api/todos` au load → render la liste
- `POST /api/todos` au submit du form → crée puis re-render
- `PUT /api/todos/{id}` au toggle checkbox → marque done
- `DELETE /api/todos/{id}` au clic ✕ → supprime puis re-render

### 6. Côté serveur

```java
// TodoService.java
@ApplicationScoped
public class TodoService {
    private final Map<Long, Todo> store = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);
    // CRUD methods…
}

// TodoResource.java
@Path("/todos")
@ApplicationScoped
public class TodoResource {
    @Inject TodoService service;
    // @GET, @POST, @PUT, @DELETE…
}
```

Quand une requête arrive, `Invoker` appelle `beanProvider.getBean(TodoResource.class)` → Vauban retourne le proxy CDI avec `service` déjà injecté. Le scope `@ApplicationScoped` garantit qu'il n'y a qu'une instance partagée.

## Endpoints

| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/` | UI HTML (index.html) |
| `GET` | `/style.css` | CSS |
| `GET` | `/app.js` | JS client |
| `GET` | `/api/greetings/{name}` | "Hello, {name}!" |
| `GET` | `/api/todos` | Liste JSON |
| `POST` | `/api/todos` | Crée |
| `GET` | `/api/todos/{id}` | Récupère |
| `PUT` | `/api/todos/{id}` | Met à jour |
| `DELETE` | `/api/todos/{id}` | Supprime |

Voir `src/test/resources/http/vauban-examples.http` pour le HTTP Client IntelliJ.

## Tests

```bash
mvn -pl cassini-examples/cassini-examples-vauban test
```

15 tests :
- `GreetingResourceTest` (3) — API greetings via CDI
- `TodoResourceTest` (7) — CRUD complet via CDI
- `StaticUiTest` (5) — vérifie `/`, `/style.css`, `/app.js`, 404, coexistence API+statique

`ExampleServer` réutilise exactement le même `VaubanApp.composeHandler()` que `Main.java` : tests et prod ont la même configuration.

## Pourquoi c'est plus complexe que les autres exemples ?

1. **Bootstrap manuel Chappe** au lieu de `SeBootstrap` — nécessaire pour servir le statique sur `/` (SeBootstrap ne donne pas accès au handler racine).
2. **Préfixe `/api`** — sans ça, les routes JAX-RS seraient en concurrence avec les fichiers statiques.
3. **Composite handler** — pattern classique pour mixer plusieurs responsabilités sur un même serveur HTTP.

Les détails sont encapsulés dans `VaubanApp.java` — `Main.java` reste simple et lisible.

## Voir aussi

- [`cassini-examples/README.md`](../README.md) — découverte du `BeanProvider`, conflits transports.
- [`cassini-examples-chappe`](../cassini-examples-chappe) — Mode A pur sans CDI (comparaison directe).
- [`cassini-cdi-vauban`](../../cassini-cdi-vauban) — adapter Vauban : `VaubanBeanProvider`, `VaubanCDIProvider`.
