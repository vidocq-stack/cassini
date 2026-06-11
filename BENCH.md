# BENCH.md — cassini

Performance figures log. Convention: see `../CLAUDE.md` (workspace root) — every
published number must have an entry here (date, hardware/JVM, exact command,
raw results, delta vs previous run).

---

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
