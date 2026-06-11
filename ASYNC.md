# Async, SSE and streaming in Cassini

## Current state (post-commit `4ca6b0e`)

### What works

| Functionality | Transport | Behavior | TCK |
|---|---|---|---|
| `@Suspended AsyncResponse` | Chappe + JDK | Blocks a virtual thread until `resume()` | ✅ passes |
| `CompletionStage<T>` return | Chappe + JDK | Blocks a virtual thread until completion | ✅ passes |
| Buffered SSE | Chappe | Events accumulated in memory, sent in one block on `sink.close()` | ✅ passes (except 3 streaming tests) |
| Chunked SSE streaming | JDK | Progressive push through direct `OutputStream` | ✅ passes |

### What does not work

| Functionality | Transport | Reason |
|---|---|---|
| Chunked SSE streaming | Chappe | Architectural deadlock (see below) |
| `addCompletionCallback` / `addConnectionCallback` | all | Not implemented (`CassiniAsyncContext` declared, not wired) |

---

## Why Chappe cannot stream SSE as-is

### The problem: `Handler` is synchronous

The central Chappe interface is:

```java
@FunctionalInterface
interface Handler {
    Response handle(Request request) throws Exception;
}
```

Chappe calls `handle()`, receives a complete `Response`, then reads the body.
The only form of streaming is `Body.streaming(InputStream)` — Chappe reads
from the `InputStream` **after** `handle()` has returned.

### The deadlock

In `ChappeHttpAdapter.handle()`, the Invoker runs inside `scoped.runInScope()`,
which is **synchronous**:

```
ChappeHttpAdapter.handle()
└── scoped.runInScope()                ← blocks until invoke completes
    └── invoker.invoke()
        └── resourceMethod.invoke()    ← the resource method runs here
            └── sseSink.send(event)   ← writes into pos (PipedOutputStream)
```

If the resource method writes to the pipe AND does not return control
(event loop, `awaitClose()`, etc.), `scoped.runInScope()` never returns.
But Chappe cannot start reading `pis` until after `handle()` has returned
`Body.streaming(pis)`. **Circular deadlock.**

Even if the resource method returns quickly (async pattern), there is
a window where `pos` can be closed BEFORE Chappe has started reading
from it — in that case it works, but it is fragile and not guaranteed.

The implementation attempt (`PipedInputStream`/`PipedOutputStream` in
`ChappeHttpExchange.openForStreaming`) caused a hang on the TCK test
`sseeventsource.JAXRSClientIT#wait2Seconds` and was removed.

---

## SSE streaming with Chappe: it is possible, here is how

The pipe approach is correct — the only problem is the synchronous
coupling between Invoker execution and the return of `handle()`.

### Solution: concurrent VT + signaling

Modify `ChappeHttpAdapter.handle()` to run the Invoker on a separate VT
and synchronize on a "streaming ready" latch:

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
                // openForStreaming() will release the latch as soon as the pipe is created
                exchange.setStreamingLatch(streamingReady);
                responseFuture.complete(invoker.invoke(candidates, exchange));
            } catch (Exception e) {
                responseFuture.completeExceptionally(e);
                streamingReady.countDown(); // unblock on error
            }
        });
    });

    // Wait: either streaming is enabled, or the full response is ready
    streamingReady.await(30, TimeUnit.SECONDS);  // or configurable timeout

    var pis = (PipedInputStream) exchange.getAttribute("cassini.streaming_pis");
    if (pis != null) {
        // Streaming mode: the VT keeps writing into pos while
        // Chappe reads from pis → native chunked transfer
        var b = Response.builder().status(StatusCode.of(exchange.collectedStatus()));
        exchange.collectedHeaders().forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));
        return b.body(Body.streaming(pis)).build();
    }

    // Normal mode: wait for the full response
    var out = responseFuture.get();
    return toChappe(out);
}
```

And in `ChappeHttpExchange.openForStreaming()`:
```java
@Override
public CassiniStreamingSink openForStreaming(int status, Map<String, List<String>> headers) {
    // ... create pis/pos ...
    setAttribute("cassini.streaming_pis", pis);
    if (streamingLatch != null) streamingLatch.countDown(); // ← signal
    return new CassiniStreamingSink() { /* writeChunk, flush, close via pos */ };
}
```

### What this unlocks

- **Async pattern** (resource spawns a VT, returns immediately):
  the latch is released as soon as `openForStreaming()` runs, Chappe starts reading,
  the background VT writes the events. ✅

- **Synchronous loop pattern** (resource writes in a loop until disconnect):
  same thing — the latch is released when the pipe is created,
  Chappe starts reading, the resource method runs on its VT and writes.
  The pipe creates natural backpressure (8 KB buffer). ✅

- **Broadcaster** (N clients, background thread writes to all):
  each request spawns its VT, creates its pipe, releases its latch. The
  broadcaster writes into N concurrent pipes. Chappe reads each one on
  its own thread. ✅

### Estimated effort

~1 day. Most of the code is already in place:
- `CassiniStreamingSink` SPI ✅
- `Body.streaming(InputStream)` in Chappe ✅ (tested in `StreamingBodyTest`)
- `openForStreaming()` in `CassiniHttpExchange` (default = null) ✅
- Remaining work: `CountDownLatch` in `ChappeHttpAdapter` + override in
  `ChappeHttpExchange` + re-enable the 3 SSE challenges in `TckChallengeExclusions`

---

## M2h — real non-blocking async

### Current situation

`@Suspended AsyncResponse` and `CompletionStage<T>` **work** thanks
to virtual threads: the VT handling the request blocks on `.get()` without
occupying an OS thread. In practice, zero starvation.

However, this is **blocking-under-the-hood**:

| What we do | What M2h would do |
|---|---|
| `cs.toCompletableFuture().get()` | `dispatch()` returns a `CompletionStage<Void>` propagated to the transport |
| ThreadLocals (`CURRENT_MATCH`, etc.) | `ScopedValue` or `CassiniHttpExchange` attributes |
| `Async.awaitBlocking(cs)` | non-blocking propagation |

### Why this is acceptable now

With JDK 25 VTs, a `.get()` in a VT **yields** its carrier thread
without blocking it. Throughput stays excellent as long as the number of
pending requests does not exceed VT memory capacity (a few KB each,
vs MB for an OS thread).

### Remaining blocking sites

| File | Code | TODO |
|---|---|---|
| `Invoker.java:787` | `cs.toCompletableFuture().get()` | M2h: propagate into `dispatch → CompletionStage<Void>` |
| `Invoker.java:768` | `asyncResponse.completionFuture().get()` | M2h: same |
| `Invoker.java:816` | `sseSink.awaitClose()` (buffered mode) | M2i: SSE streaming → no longer needed |

### ThreadLocals to migrate for real M2h — ✅ DONE (2026-06-11)

`cassini-core` is now **ThreadLocal-free**. One `RequestScope` (a per-request
holder carried by a `ScopedValue`, bound once per dispatch in
`DefaultCassiniHttpAdapter` and defensively by the public `Invoker` entry
points) replaced them all:

| File | Was | Now |
|---|---|---|
| `Invoker.java` | `CURRENT_MATCH`, `CURRENT_REQUEST` | `RequestScope` slots (`Invoker.currentMatch()/currentRequest()`) |
| `CassiniRequest.java` | `PENDING_VARY` | `CassiniHttpExchange` attribute (earlier chantier) |
| `CassiniSecurityContext.java` | `CURRENT_AUTH` (inheritable) | exchange attribute `cassini.auth` (captured by reference — readable from async-spawned threads) |
| `FieldInjector.java` | `FORM_CACHE`, etc. | `CassiniHttpExchange` attributes (earlier chantier) |
| `ParamExtractor.java` | `PROVIDERS`, `APPLICATION` (inheritable), `PCPS`, `SINK` | `RequestScope` slots; the Application is an `Invoker` field seeded per request |
| `CassiniResponseBuilder.java` | `BASE_URI` | `RequestScope` slot |
| `ExceptionMapperRegistry.java` | `MAPPING` recursion guard | lexical `ScopedValue` rebinding |

Note: the upstream→Cassini handover (BASIC auth set before the per-request VT
spawn) goes through the Chappe per-request attributes, bridged by
`ChappeHttpExchange.getAttribute` (cf. CASSINI-002 in BUG.md).

### Estimated effort for full M2h

~8-13 days. The invariants are already prepared:
- `CassiniHttpAdapter.dispatch → CompletionStage<Void>` ✅
- `CassiniAsyncContext` SPI ✅ (not wired on the transport side)
- `Async.awaitBlocking()` centralizes all `.get()` calls ✅ (makes grep easier)

---

## Recommended priorities

| Order | Task | Effort | Unlocks |
|---|---|---|---|
| 1 | **Chappe SSE streaming** (VT + latch in `ChappeHttpAdapter`) | ~1 d | 3 SSE TCK challenges |
| 2 | **Real M2h async** (`CompletionStage` propagation, `ScopedValue`s) | ~8-13 d | 10 async TCK tests, maximum scalability |
| 3 | `addCompletionCallback` / `addConnectionCallback` | ~0.5 d | §8.2 callback compliance |
