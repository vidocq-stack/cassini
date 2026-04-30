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

## 4. Décisions à prendre

- [ ] **Module path** : `io.vidocq.cassini.jsonp` et `io.vidocq.cassini.jsonb`
      ou `io.vidocq.jsonp`/`io.vidocq.jsonb` (potentiellement utiles hors
      Cassini) ?
- [ ] **Optionnel ou par défaut** : Yasson reste-t-il un fallback supporté
      pour Cassini, ou on ne livre que `cassini-jsonb` ?
- [ ] **JSON-P comme dépendance distincte** : extraire `cassini-jsonp` même
      si `cassini-jsonb` est seul consommateur, ou les fondre en un seul
      module ?
