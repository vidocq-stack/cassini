# Async, SSE et streaming dans Cassini

## État actuel (post-commit `4ca6b0e`)

### Ce qui fonctionne

| Fonctionnalité | Transport | Comportement | TCK |
|---|---|---|---|
| `@Suspended AsyncResponse` | Chappe + JDK | Bloque un virtual thread jusqu'à `resume()` | ✅ passe |
| `CompletionStage<T>` return | Chappe + JDK | Bloque un virtual thread jusqu'à complétion | ✅ passe |
| SSE bufferisé | Chappe | Events accumulés en mémoire, envoyés en bloc à `sink.close()` | ✅ passe (sauf 3 tests streaming) |
| SSE streaming chunked | JDK | Push au fil de l'eau via `OutputStream` direct | ✅ passe |

### Ce qui ne fonctionne pas

| Fonctionnalité | Transport | Raison |
|---|---|---|
| SSE streaming chunked | Chappe | Deadlock architectural (voir ci-dessous) |
| `addCompletionCallback` / `addConnectionCallback` | tous | Non implémenté (`CassiniAsyncContext` déclaré, non câblé) |

---

## Pourquoi Chappe ne peut pas streamer SSE en l'état

### Le problème : `Handler` est synchrone

L'interface centrale de Chappe est :

```java
@FunctionalInterface
interface Handler {
    Response handle(Request request) throws Exception;
}
```

Chappe appelle `handle()`, reçoit une `Response` complète, puis lit le body.
La seule forme de streaming est `Body.streaming(InputStream)` — Chappe lit
depuis l'InputStream **après** que `handle()` a retourné.

### Le deadlock

Dans `ChappeHttpAdapter.handle()`, l'Invoker tourne dans `scoped.runInScope()`,
qui est **synchrone** :

```
ChappeHttpAdapter.handle()
└── scoped.runInScope()                ← bloque jusqu'à fin de l'invoke
    └── invoker.invoke()
        └── resourceMethod.invoke()    ← la méthode resource tourne ici
            └── sseSink.send(event)   ← écrit dans pos (PipedOutputStream)
```

Si la méthode resource écrit dans la pipe ET ne rend pas la main (boucle
événementielle, `awaitClose()`, etc.), `scoped.runInScope()` ne retourne
jamais. Or Chappe ne peut commencer à lire `pis` qu'après que `handle()`
a retourné `Body.streaming(pis)`. **Deadlock circulaire.**

Même si la méthode resource rend la main rapidement (pattern async), il
existe une window où `pos` peut être fermé AVANT que Chappe ait commencé
à lire — dans ce cas ça fonctionne, mais c'est fragile et non garanti.

La tentative d'implémentation (`PipedInputStream`/`PipedOutputStream` dans
`ChappeHttpExchange.openForStreaming`) a provoqué un hang sur le test TCK
`sseeventsource.JAXRSClientIT#wait2Seconds` et a été retirée.

---

## SSE streaming avec Chappe : c'est possible, voici comment

L'approche pipe est correcte — le problème est uniquement le couplage
synchrone entre l'exécution de l'Invoker et le retour de `handle()`.

### Solution : VT concurrent + signaling

Modifier `ChappeHttpAdapter.handle()` pour exécuter l'Invoker sur un VT
séparé et synchroniser sur un latch "streaming prêt" :

```java
@Override
public Response handle(Request request) throws Exception {
    var exchange = new ChappeHttpExchange(request);
    // ... routing ...

    var streamingReady = new CountDownLatch(1);
    var responseFuture = new CompletableFuture<CassiniHttpResponse>();

    Thread.startVirtualThread(() -> {
        scoped.runInScope(() -> {
            try {
                // openForStreaming() libèrera le latch dès la pipe créée
                exchange.setStreamingLatch(streamingReady);
                responseFuture.complete(invoker.invoke(candidates, exchange));
            } catch (Exception e) {
                responseFuture.completeExceptionally(e);
                streamingReady.countDown(); // débloquer si erreur
            }
        });
    });

    // Attendre : soit streaming activé, soit réponse complète
    streamingReady.await(30, TimeUnit.SECONDS);  // ou timeout configuré

    var pis = (PipedInputStream) exchange.getAttribute("cassini.streaming_pis");
    if (pis != null) {
        // Mode streaming : le VT continue d'écrire dans pos pendant que
        // Chappe lit depuis pis → chunked transfer natif
        var b = Response.builder().status(StatusCode.of(exchange.collectedStatus()));
        exchange.collectedHeaders().forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));
        return b.body(Body.streaming(pis)).build();
    }

    // Mode normal : attendre la réponse complète
    var out = responseFuture.get();
    return toChappe(out);
}
```

Et dans `ChappeHttpExchange.openForStreaming()` :
```java
@Override
public CassiniStreamingSink openForStreaming(int status, Map<String, List<String>> headers) {
    // ... créer pis/pos ...
    setAttribute("cassini.streaming_pis", pis);
    if (streamingLatch != null) streamingLatch.countDown(); // ← signal
    return new CassiniStreamingSink() { /* writeChunk, flush, close via pos */ };
}
```

### Ce que ça débloque

- **Pattern async** (resource spawne un VT, retourne immédiatement) :
  le latch est libéré dès `openForStreaming()`, Chappe commence à lire,
  le VT background écrit les events. ✅

- **Pattern sync boucle** (resource écrit en boucle jusqu'à disconnect) :
  même chose — le latch est libéré au moment de la création de la pipe,
  Chappe commence à lire, la méthode resource tourne sur son VT et écrit.
  La pipe crée la backpressure naturelle (8 Ko de buffer). ✅

- **Broadcaster** (N clients, background thread écrit à tous) :
  chaque requête spawne son VT, créé sa pipe, libère son latch. Le
  broadcaster écrit dans N pipes concurrentes. Chappe lit chacune sur
  son propre thread. ✅

### Effort estimé

~1 jour. L'essentiel du code est déjà en place :
- `CassiniStreamingSink` SPI ✅
- `Body.streaming(InputStream)` Chappe ✅ (testé dans `StreamingBodyTest`)
- `openForStreaming()` dans `CassiniHttpExchange` (default = null) ✅
- Il reste : `CountDownLatch` dans `ChappeHttpAdapter` + override dans
  `ChappeHttpExchange` + ré-activer les 3 challenges SSE dans `TckChallengeExclusions`

---

## M2h — Async vrai non-bloquant

### Situation actuelle

`@Suspended AsyncResponse` et `CompletionStage<T>` **fonctionnent** grâce
aux virtual threads : le VT qui traite la requête bloque sur `.get()` sans
occuper de thread OS. En pratique, zéro starvation.

Cependant c'est du **blocking-under-the-hood** :

| Ce qu'on fait | Ce que M2h ferait |
|---|---|
| `cs.toCompletableFuture().get()` | `dispatch()` retourne un `CompletionStage<Void>` propagé jusqu'au transport |
| ThreadLocals (`CURRENT_MATCH`, etc.) | `ScopedValue` ou attributs `CassiniHttpExchange` |
| `Async.awaitBlocking(cs)` | propagation non-bloquante |

### Pourquoi c'est acceptable maintenant

Avec les VT JDK 25, un `.get()` dans un VT **yield** son carrier thread
sans le bloquer. Le débit reste excellent tant que le nombre de requêtes
en attente ne dépasse pas la capacité mémoire des VT (quelques Ko chacun,
vs Mo pour un thread OS).

### Sites de blocage résiduels

| Fichier | Code | TODO |
|---|---|---|
| `Invoker.java:787` | `cs.toCompletableFuture().get()` | M2h : propager dans `dispatch → CompletionStage<Void>` |
| `Invoker.java:768` | `asyncResponse.completionFuture().get()` | M2h : même |
| `Invoker.java:816` | `sseSink.awaitClose()` (mode bufferisé) | M2i : SSE streaming → plus besoin |

### ThreadLocals à migrer pour M2h vrai

Si on veut un async entièrement non-bloquant (pas de blocage même sur VT),
les ThreadLocals cassent si le VT yield entre deux accès :

| Fichier | ThreadLocal | Migration cible |
|---|---|---|
| `Invoker.java` | `CURRENT_MATCH`, `CURRENT_REQUEST`, `CURRENT_MATCHED_RESOURCES` | `ScopedValue` ou attribut `CassiniHttpExchange` |
| `CassiniRequest.java` | `PENDING_VARY` | attribut `CassiniHttpExchange` |
| `CassiniSecurityContext.java` | `CURRENT_AUTH` | attribut `CassiniHttpExchange` |
| `FieldInjector.java` | `FORM_CACHE`, `FORM_CACHE_ENCODED`, `BODY_CACHE` | attribut `CassiniHttpExchange` |
| `ParamExtractor.java` | `PROVIDERS`, etc. | `ScopedValue` |
| `CassiniResponseBuilder.java` | `BASE_URI` | `ScopedValue` |

### Effort estimé M2h complet

~8-13 jours. Les invariants sont déjà préparés :
- `CassiniHttpAdapter.dispatch → CompletionStage<Void>` ✅
- `CassiniAsyncContext` SPI ✅ (non câblé côté transport)
- `Async.awaitBlocking()` centralise tous les `.get()` ✅ (facilite le grep)

---

## Priorités recommandées

| Ordre | Tâche | Effort | Débloque |
|---|---|---|---|
| 1 | **SSE streaming Chappe** (VT + latch dans ChappeHttpAdapter) | ~1 j | 3 challenges TCK SSE |
| 2 | **M2h async vrai** (propagation CompletionStage, ScopedValues) | ~8-13 j | 10 tests async TCK, scalabilité maximale |
| 3 | `addCompletionCallback` / `addConnectionCallback` | ~0.5 j | compliance §8.2 callbacks |
