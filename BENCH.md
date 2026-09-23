# BENCH.md — cassini

Performance figures log. Convention: see `../CLAUDE.md` (workspace root) — every
published number must have an entry here (date, hardware/JVM, exact command,
raw results, delta vs previous run).

---

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
