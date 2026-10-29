# BENCH.md — cassini

Performance figures log. Convention: see `../CLAUDE.md` (workspace root) — every
published number must have an entry here (date, hardware/JVM, exact command,
raw results, delta vs previous run).

---

## 2026-10-29 — Object sub-resource locators: the returned class scanned once

**Purpose**: a sub-resource locator that returns `Object` is routed at run time on the class of what it returned
(§3.4.1). `DynamicLocatorDispatch` scanned that class and built a router for it on every request. Its router is now
built once per class (`ClassValue`), and the `setAccessible` calls the request path repeated on methods the
scanner had already opened are gone.

- **Commit**: `67a1e09` (main) against branch `perf/locator-hot-path`.
- **Hardware / JVM**: Apple M5 Max, 18 cores, 128 GB RAM, OpenJDK 25 LTS (Temurin 25+36), macOS (Darwin 27.0.0).
- **Harness**: `SubResourceLocatorTest.anObjectLocatorDoesNotScanWhatItReturnsOnEveryRequest` dispatches in-memory
  requests through `CassiniStack.adapter().dispatch` (no socket) and reads the bytes the thread allocates
  (`Allocations.bytesPerCall`, 2 000 unmeasured then 5 000 measured requests). `GET /loc/typed/7` goes through a
  locator typed with its sub-resource class (routed at startup), `GET /loc/dyn` through one returning `Object`, to
  the same sub-resource. A `System.out` of the two figures was added for the measurement only; "before" is the same
  test on main's `DynamicLocatorDispatch`.
- **Command** (three runs after, three before):
  ```bash
  ./mvnw -ntp test -pl cassini-core -Dtest='SubResourceLocatorTest'
  ```
- **Raw results** (bytes allocated per request):

  | Route | Before | After |
  |---|---|---|
  | typed locator, `GET /loc/typed/7` | 6 696 / 6 682 / 6 685 B | 6 671 / 6 677 / 6 695 B |
  | `Object` locator, `GET /loc/dyn` | 33 866 / 34 008 / 33 950 B | 6 518 / 6 512 / 6 512 B |

- **Delta vs previous run**: first entry for this measure.
- **Conclusion**: a request through an `Object` locator allocated about **27 KB more** than through a typed one, the
  scan of the returned class and its router. It now costs the same as the typed route (slightly less: no locator
  chain to inject for the sub-resource's own route).

## 2026-10-29 — Parameter conversion: the §3.2 factory of a type looked up once

**Purpose**: `ParamValueConverter` converts a method parameter, and a field the generated adapter cannot convert
inline, through the type's `valueOf(String)`, `fromString(String)` or `String` constructor (§3.2). It looked the
factory up for every value, and `getDeclaredMethod` throws a `NoSuchMethodException`, stack trace included, for
each candidate the type does not have. The factory of each type is now resolved once (`ClassValue`).

- **Commit**: `67a1e09` (main) against branch `perf/param-factory-cache`.
- **Hardware / JVM**: Apple M5 Max, 18 cores, 128 GB RAM, OpenJDK 25 LTS (Temurin 25+36), macOS (Darwin 27.0.0).
- **Harness**: `ParamValueConverterTest.aFactoryIsLookedUpOncePerType`, which reads the bytes the current thread
  allocates (`Allocations.bytesPerCall`, HotSpot's exact per-thread accounting) over 10 000 conversions after
  2 000 unmeasured ones. A `System.out` of the three figures was added for the measurement only; the "before" run
  is the same test on main's `ParamValueConverter`.
- **Command** (three runs each):
  ```bash
  ./mvnw -ntp test -pl cassini-core -Dtest='ParamValueConverterTest#aFactoryIsLookedUpOncePerType'
  ```
- **Raw results** (bytes allocated per converted value, three runs):

  | Type | Factory | Before | After |
  |---|---|---|---|
  | `java.util.UUID` | `fromString` (no `valueOf`) | 3 056 / 3 054 / 3 055 B | 56 / 56 / 56 B |
  | application class | `String` constructor (no `valueOf`, no `fromString`) | 5 921 / 5 930 / 5 928 B | 40 / 40 / 40 B |
  | enum | `fromString` | 168 / 168 / 168 B | 24 / 24 / 24 B |

  "After" is the converted value itself (a `UUID` and its two longs, the application object) plus the argument
  array of `Method.invoke`.

- **Delta vs previous run**: first entry for this measure.
- **Conclusion**: a type without `valueOf` paid about 3 KB of garbage per value, and one with only a `String`
  constructor about 6 KB: one exception per missing candidate. Both now allocate only their result. The conversion
  rules and their order are unchanged.

## 2026-09-23 — Request counters (cassini#42): cost of `CassiniStack.statistics()`

**Purpose**: cassini#42 adds counters to the request path (requests, in flight, by status class, total and max
time). The issue asks whether they must be off by default or are free enough to stay on, stated as a number.

- **Hardware / JVM**: Apple M5 Max, 18 cores, 128 GB RAM, OpenJDK 25 LTS (Temurin 25+36), macOS.
- **Harness**: `RequestStatisticsBench` (cassini-core, opt-in). Two stacks built from the same `Application`, one with
  `statistics(true)`, one with `statistics(false)`, serve an in-memory `GET /ping` through
  `DefaultCassiniHttpAdapter.dispatch` — no socket, so the counters are not lost in network noise, which makes this
  the worst case. 4 warm-up rounds, then 12 rounds alternating which stack goes first; the median round is kept.
  Allocation is read with `ThreadMXBean.getThreadAllocatedBytes` over 50 000 requests on one thread.
- **Command**:
  ```bash
  ./mvnw -ntp -pl cassini-core test -Dtest=RequestStatisticsBench -Dcassini.bench=true
  # single client, for the latency of one request:
  ./mvnw -ntp -pl cassini-core test -Dtest=RequestStatisticsBench -Dcassini.bench=true \
      -Dcassini.bench.threads=1 -Dcassini.bench.perThread=200000
  ```
- **Raw results** (median ns per request; allocated bytes per request):

  | Run | Clients | With counters | Without | Delta | Allocated, with / without |
  |---|---|---|---|---|---|
  | 1 | 64 virtual threads × 5 000 | 176.1 ns | 151.4 ns | +24.7 ns (+16.3 %) | 9 216 / 9 216 B |
  | 2 | 64 virtual threads × 5 000 | 163.2 ns | 139.5 ns | +23.6 ns (+16.9 %) | 9 216 / 9 216 B |
  | 3 | 64 virtual threads × 5 000 | 160.8 ns | 141.6 ns | +19.3 ns (+13.6 %) | 9 000 / 9 000 B |
  | 4 | 1 virtual thread × 200 000 | 1 662.4 ns | 1 636.4 ns | +26.0 ns (+1.6 %) | 9 272 / 9 272 B |

  With 64 clients the figure is throughput (18 cores share the work), with one client it is the latency of a request.

- **Delta vs previous run**: first entry.
- **Conclusion**: the counters cost **about 20-26 ns per request and allocate nothing**. The cost is the same with one
  client and with 64, so it is the two `System.nanoTime()` reads, not contention: the `LongAdder`s absorb 64
  concurrent writers. Against the latency of an in-memory request, 1.64 µs, that is +1.6 %; a request that crosses
  a socket takes tens of microseconds, where 26 ns is below 0.1 %. **Statistics are on by default**;
  `CassiniStack.Builder.statistics(false)` removes them, and the clock reads with them.

## 2026-06-11 — Suspended-load: 10 000 concurrent `@Suspended AsyncResponse`

**Purpose**: measure the real cost of the blocking-on-virtual-thread model for
suspended async responses, to decide whether the "full M2h" CompletionStage
propagation to the transport (reactive-style, ~8-13 d + a Chappe async SPI) is
worth doing. Each suspended request holds a Chappe connection VT + a cassini-req
VT, both parked.

- **Hardware / JVM**: Apple M4 Max, 128 GB RAM, OpenJDK 25 LTS (Temurin), macOS.
- **Command**:
  ```bash
  ulimit -n 65536
  ./mvnw -ntp test -pl cassini-examples/cassini-examples-chappe \
      -Dtest=SuspendedLoadBench -Dcassini.bench=true -Dcassini.bench.n=10000
  ```
- **Raw results** (in-process harness: server + raw-socket clients in one JVM,
  heap sampled after 3×GC):

  | Metric | Value |
  |---|---|
  | N suspended | 10 000 |
  | park time (open+send+all suspended) | 925 ms |
  | heap before | 6.1 MB |
  | heap with N suspended | 208.4 MB |
  | **heap delta** | **202.3 MB → 20.7 KB/request** (incl. the 10 000 client-side sockets in the same JVM) |
  | release + 10 000 × HTTP 200 received | 1 855 ms |
  | correctness | 10 000/10 000 responses 200 |

  Control run at N=5 000: 22.6 KB/request — linear.

- **Delta vs previous run**: first entry.
- **Conclusion**: ~20 KB of heap per suspended request (client sockets
  included — the server-side share is lower), zero platform threads blocked,
  sub-second suspension of 10k requests and ~1.9 s to deliver 10k responses at
  once. Extrapolated, 100k concurrent suspended requests cost ~2 GB heap
  worst-case. **The CompletionStage-propagation chantier is not worth its cost
  and is dropped as a non-goal** (decision recorded in `ASYNC.md`); revisit
  only with a real use case whose numbers contradict these.
