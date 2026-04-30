# JSON-B / JSON-P — état actuel et roadmap d'implémentation maison

**Statut** : Cassini utilise aujourd'hui Yasson 3.0.4 (JSON-B) + Parsson 1.1.7
(JSON-P) en provider runtime. Plusieurs frottements en mode JPMS strict
(jlink / jpackage / module-path) justifient à terme une **implémentation
maison** alignée sur les contraintes Cassini : `cassini-jsonb` et `cassini-jsonp`.

Ce document trace les bugs/workarounds connus et les cibles fonctionnelles
pour l'impl maison.

---

## 1. Bug Yasson + `record` + module-path strict (avril 2026)

### Symptôme

Une ressource JAX-RS qui consomme/produit un `record` voit le composant
`String` (et possiblement les autres références) revenir `null` après
round-trip POST → GET, **uniquement** quand l'app tourne en module-path
strict (image jlink ou bundle jpackage). En classpath (`mvn exec:java`,
`java -cp`) le même code marche.

Reproduction : `vidocq-mps-rest-example` (todo-list) avant le fix.

```java
public record Todo(long id, String title, boolean done) {}
```

```http
POST /api/todos {"title":"Pain","done":false}
→ 201 {"done":false,"id":1}                 (title perdu, jamais sérialisé)
```

### Cause racine

Yasson désérialise un record via le canonical constructor implicite
(`Todo(long, String, boolean)`). En module-path strict, la résolution du
canonical constructor par réflexion échoue silencieusement même avec
`opens io.vidocq.mpserver.examples.rest;` unconditional dans le module-info,
parce que :

- Le canonical constructor d'un record n'a pas d'`@JsonbCreator` explicite ;
- Yasson tombe en mode "no-arg + setters" qui n'existent pas pour un record ;
- Résultat : tous les composants gardent leur valeur par défaut (`null` pour
  `String`, `0` pour primitifs).

Yasson sérialise ensuite l'objet — `title=null` et la config Yasson par
défaut est `nillable=false`, donc le champ est omis.

### Workaround actuel

Annoter une factory `static` avec `@JsonbCreator` :

```java
public record Todo(long id, String title, boolean done) {
    @JsonbCreator
    public static Todo create(@JsonbProperty("id") long id,
                              @JsonbProperty("title") String title,
                              @JsonbProperty("done") boolean done) {
        return new Todo(id, title, done);
    }
}
```

Yasson résout la factory par les noms `@JsonbProperty` et l'invoque comme
méthode publique standard — pas de privilèges réflexion supplémentaires
requis.

Implémenté pour `vidocq-mps-rest-example/Todo.java` au commit `86934af`.

### Tradeoffs

- **Pour** : fix minimaliste, pas de dépendance ajoutée, fonctionne
  immédiatement en jlink/jpackage.
- **Contre** : boilerplate qui se répète sur chaque record sérialisé via
  REST. Pas découvrable — un dev oublie l'annotation et le bug revient
  silencieusement (champs absents, pas d'erreur).

---

## 2. Autres limitations Yasson observées ou anticipées

| # | Limitation | Impact | Workaround |
|---|------------|--------|------------|
| 2.1 | Records sans `@JsonbCreator` cassés en module-path | Bloquant en prod jlink | Factory `@JsonbCreator` (cf. §1) |
| 2.2 | Configurabilité réduite via `JsonbConfig` (pas de `@JsonbAdapter` global, formats date verbeux) | Friction sur usages avancés | Adapter par champ |
| 2.3 | `@Generated` BeanPropertyVisibility mais pas de support `JsonbVisibility` custom | Limite l'override par projet | Subclasser `JsonbAdapter` |
| 2.4 | Démarrage : ~80 ms d'init JNDI/CDI à la première requête | Coût premier hit | Pré-warm au boot |

---

## 3. Cible : `cassini-jsonb` + `cassini-jsonp` maison

### Pourquoi

- **Records first-class** : pas de boilerplate `@JsonbCreator` requis ; le
  canonical constructor est résolu via `Class.getRecordComponents()` qui ne
  demande pas d'opens spécifique.
- **Module-path natif** : module Java propre, déclarations `provides
  jakarta.json.bind.spi.JsonbProvider with cassini.jsonb.CassiniJsonbProvider`.
- **Démarrage instantané** : pas de scan JNDI/CDI au boot ; binding par
  Lookup MethodHandle créé à la première utilisation puis cached.
- **Streaming-first** : pour les bodies SSE et chunked, lecture/écriture par
  `Flow.Publisher<ByteBuffer>` au lieu de `byte[]` complet en mémoire (cf.
  `Body.streaming()` côté Chappe).
- **Zéro Reflection runtime non nécessaire** : utiliser `MethodHandles.Lookup`
  + `LambdaMetafactory` pour les accesseurs de records et les setters POJO.
  Reflection uniquement au lookup initial.
- **Test-driven via TCK** : aligner sur le TCK Jakarta JSON-B 3.0 pour rester
  conforme.

### Périmètre

| Module | Rôle | Statut |
|--------|------|--------|
| `cassini-jsonb` | Implémentation Jakarta JSON-B 3.0 | Backlog |
| `cassini-jsonp` | Implémentation Jakarta JSON-P 2.1 (JsonParser/JsonGenerator) | Backlog |

`cassini-jsonp` est utile parce que Yasson en a besoin (StAX-like API). Si on
fait `cassini-jsonp` propre, on peut le partager avec d'autres consommateurs
JSON-P (logging structuré, config, etc.).

### Plan d'attaque pressenti

1. **`cassini-jsonp` d'abord** (plus simple, plus contenu).
   - Tokenizer + `JsonParser` (pull-based, streaming).
   - `JsonGenerator` (push-based, streaming, indenté optionnel).
   - `JsonObject`/`JsonArray` builders.
   - TCK JSON-P 2.1 → cible 100 %.

2. **`cassini-jsonb` ensuite**, monté sur `cassini-jsonp`.
   - Resolver `RecordComponents` via `MethodHandles`.
   - Adapters built-in : `Instant`, `LocalDate`, `LocalDateTime`, `UUID`,
     `Duration`, `Optional<T>`, collections, maps, enums, sealed types.
   - `@JsonbProperty`, `@JsonbDateFormat`, `@JsonbNumberFormat`, `@JsonbAdapter`,
     `@JsonbTransient`, `@JsonbCreator` (rétrocompat).
   - TCK JSON-B 3.0 → cible 100 %.

3. **Wiring Cassini** : `MessageBodyRegistry` enregistre par défaut
   `CassiniJsonbReaderWriter` qui délègue à `cassini-jsonb` plutôt qu'à
   Yasson via `JsonbBuilder`. Yasson reste le fallback si présent en provider.

4. **Migration `vidocq-mps-rest-example`** : retirer la factory
   `@JsonbCreator` du `Todo` record une fois `cassini-jsonb` activé.

### Effort estimé

- `cassini-jsonp` : 2-3 semaines (parser + generator + builders + TCK).
- `cassini-jsonb` : 4-6 semaines (richesse de l'API + TCK conformance).

Total ~7 à 10 semaines pour avoir un stack JSON pleinement maison.

### Critères de succès

- Records first-class sans `@JsonbCreator`.
- TCK JSON-P 2.1 et JSON-B 3.0 verts à 100 %.
- Démarrage : < 10 ms entre Cassini boot et première sérialisation.
- Footprint mémoire < Yasson 3.0.4 (mesuré).
- Module JPMS clean : `provides`/`uses` + `module-info.java` propres.

---

## 4. Spécifications techniques que `cassini-jsonb` / `cassini-jsonp` DOIVENT respecter

Ces invariants viennent directement des frottements observés avec Yasson +
Parsson (cf. §1, §2). **Toute violation d'un de ces points fait échouer la
revue de design.**

### 4.1 Module-path strict ready (priorité absolue)

| # | Règle | Pourquoi | Détail d'impl |
|---|-------|----------|---------------|
| **R-1** | **Aucun appel à `setAccessible(true)`** dans le runtime path. | Demande `opens` chez le consommateur ; cassait Yasson + records. | Tout passe par `MethodHandles.publicLookup()`. Si la cible n'est pas publique, soit on lève une erreur claire au binding (pas au runtime), soit on exige un `JsonbAdapter` user. |
| **R-2** | **Pas d'appel à `Class.getDeclaredConstructors()` / `getDeclaredMethods()`**. Préférer les variantes `public*`. | Idem : nécessite des privilèges réflexion. | Records et POJOs publics → `getRecordComponents()` + `getMethods()` suffisent. Pour les types non-publics, exiger `@JsonbAdapter` ou refuser. |
| **R-3** | **Aucune dépendance à un `opens` côté consommateur**. Une app dont le `module-info.java` ne contient *aucun* `opens` doit fonctionner. | Le bug Yasson + records survient exactement là. | Tester explicitement avec un test runtime jlink où le module consommateur n'a aucun `opens`. CI obligatoire. |
| **R-4** | **Pas d'usage de `Lookup.privateLookupIn(...)`** sur le module consommateur. | Cette API requiert `opens to <module>` ciblé. | Seul `MethodHandles.publicLookup()` est acceptable pour traverser les frontières de modules. |

### 4.2 Records — first-class

| # | Règle | Détail d'impl |
|---|-------|---------------|
| **R-5** | Le **canonical constructor** d'un record est résolu via `Class.getRecordComponents()` + `MethodHandles.publicLookup().findConstructor(...)`. Pas de `getDeclaredConstructor`. | Le canonical constructor d'un record est *toujours* `public` — on a le droit de le résoudre via `publicLookup` sans opens. |
| **R-6** | Les **accesseurs** (`title()`, `id()`, `done()`) sont résolus via `publicLookup().findVirtual(...)`. Cached par `ClassValue<RecordBinding>` au premier usage. | Records accesseurs sont publics par construction. Un seul lookup amorti sur tous les appels. |
| **R-7** | **Aucune annotation requise** sur les records pour qu'ils soient sérialisables/désérialisables. `@JsonbProperty` reste optionnelle (renommage de champ, alias). | C'est ce qui distingue de Yasson 3.0.4 → suppression du boilerplate `@JsonbCreator` factory. |
| **R-8** | Composants **null** sérialisés selon la config (`nillable=true` par défaut côté `cassini-jsonb` ?  À discuter — Yasson met `false` ; pour les records l'absence d'un champ a un sens différent que `null`). | Décision ouverte (cf. §5). |
| **R-9** | Records **génériques** (`record Pair<A, B>(A first, B second)`) supportés via `Class.getRecordComponents()[i].getGenericType()`. | Préserver les TypeVariable jusqu'au binding final. |

### 4.3 JSON-P pull-based / push-based

| # | Règle | Détail d'impl |
|---|-------|---------------|
| **R-10** | `JsonParser` lit caractère par caractère via un `InputStream` ou `Reader`. **Jamais** `readAllBytes()`. | Critique pour SSE / chunked / gros payloads. Aligne sur `Body.streaming()` de Chappe. |
| **R-11** | `JsonGenerator` écrit directement sur l'`OutputStream` cible. Pas de `StringBuilder` intermédiaire. | Idem streaming. |
| **R-12** | Un `JsonParser` doit être positionnable et navigable sans tout charger en mémoire. `JsonObject` complet uniquement à la demande explicite (`getObject()`). | Cohérent avec l'API JSON-P standard, mais souvent oublié par les impls. |
| **R-13** | `JsonString.getString()` retourne le contenu décodé (sans guillemets) ; les escapes Unicode (`\uXXXX`), surrogate pairs et caractères de contrôle sont décodés correctement (TCK exige). | Fait souvent louper des caractères BMP > U+FFFF. |

### 4.4 Démarrage / coût d'init

| # | Règle | Détail d'impl |
|---|-------|---------------|
| **R-14** | `Jsonb.fromJson(...)` doit fonctionner sans aucun side-effect d'init au-delà du premier appel. **Pas d'init JNDI**, pas de scan classpath, pas de CDI lookup. | Coût constant `O(1)` au premier `JsonbBuilder.create()`. |
| **R-15** | Le binding par classe (record ou POJO) est **lazy** : résolu uniquement à la première sérialisation/désérialisation de cette classe. Cached via `ClassValue`. | Pas de scan global au boot. |
| **R-16** | Les bindings sont **immutables** une fois créés. Concurrence safe sans synchronisation explicite. | Multi-thread sans contention (HTTP server multi-virtual-threads). |
| **R-17** | Un benchmark intégré (`cassini-bench`) compare cold start + warm throughput vs Yasson sur un set représentatif (records, POJOs, dates, collections, polymorphisme). Régression > 10 % bloque le merge. | Discipline de perf. |

### 4.5 Module JPMS — `module-info.java`

```java
module io.vidocq.cassini.jsonp {
    requires transitive jakarta.json;
    exports io.vidocq.cassini.jsonp;            // si API publique au-delà du SPI
    provides jakarta.json.spi.JsonProvider
        with io.vidocq.cassini.jsonp.CassiniJsonProvider;
}

module io.vidocq.cassini.jsonb {
    requires transitive jakarta.json.bind;
    requires io.vidocq.cassini.jsonp;
    exports io.vidocq.cassini.jsonb;            // pareil, à minimiser
    provides jakarta.json.bind.spi.JsonbProvider
        with io.vidocq.cassini.jsonb.CassiniJsonbProvider;
}
```

| # | Règle | Détail |
|---|-------|--------|
| **R-18** | Aucun `requires static` sur des modules optionnels. Si un binding optionnel existe (ex. JSR-310 zone-id format), il doit être détecté via `Class.forName()` au binding. | Évite la pollution du module path. |
| **R-19** | Pas de packages `internal/` exportés sans qualifier. | Encapsulation. |
| **R-20** | Tests d'intégration jlink dans `cassini-jsonb-tests` : un sous-module `cassini-jsonb-it-jlink` qui produit une image jlink minimale + assert via curl que le round-trip records marche. | Régression-proof contre R-1 à R-7. |

### 4.6 Compatibilité TCK Jakarta

| # | Règle | Détail |
|---|-------|--------|
| **R-21** | TCK JSON-P 2.1 exécuté sur `cassini-jsonp` à chaque PR. Cible 100 % avant rétention. | Conformité. |
| **R-22** | TCK JSON-B 3.0 exécuté sur `cassini-jsonb` à chaque PR. Cible 100 % avant rétention. | Conformité. |
| **R-23** | Pas de feature non-spec exposée dans l'API publique tant que le TCK n'est pas vert. | Évite la surface d'instabilité. |

### 4.7 Diagnostic & erreurs

| # | Règle | Détail |
|---|-------|--------|
| **R-24** | Erreur de binding (record non résoluble, type non supporté) → `JsonbException` avec message explicite : nom du record, composant fautif, raison probable, suggestion. | Le mode "défaut silencieux" de Yasson + records est exactement ce qu'on veut éviter. |
| **R-25** | Un mode `JsonbConfig.withStrictMode(true)` qui transforme tout warning en erreur (champ JSON inconnu lors de la désérialisation, accesseur non-trouvé, etc.). | Aide le dev à attraper les bugs au plus tôt. |
| **R-26** | Logging via `System.Logger` (JEP 264), niveau `DEBUG` pour les bindings résolus (une ligne par classe), `WARNING` pour les fallbacks (Adapter custom utilisé, etc.). | Zéro dépendance log lib. |

---

## 5. Décisions à prendre

- [ ] **Module path** : `io.vidocq.cassini.jsonp` et `io.vidocq.cassini.jsonb`
      ou `io.vidocq.jsonp`/`io.vidocq.jsonb` (potentiellement utiles hors
      Cassini) ?
- [ ] **Optionnel ou par défaut** : Yasson reste-t-il un fallback supporté
      pour Cassini, ou on ne livre que `cassini-jsonb` ?
- [ ] **JSON-P comme dépendance distincte** : extraire `cassini-jsonp` même
      si `cassini-jsonb` est seul consommateur, ou les fondre en un seul
      module ?
- [ ] **Records `null` policy** (cf. R-8) : `nillable=true` par défaut
      (champs `null` sérialisés explicitement) ou `false` (omission) ?
      Le comportement Yasson par défaut est `false` — c'est ce qui a masqué
      le bug §1. Pencher vers `true` pour `cassini-jsonb` ?
