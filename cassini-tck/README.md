# vidocq-rest-cassini-tck-runner

**Jakarta RESTful Web Services 4.0** conformance harness for the
Cassini extension. Maven module **intentionally outside** the main Vidocq
reactor — see `vidocq-core-extensions/pom.xml`.

## Why outside the reactor?

The official Jakarta REST 4.0 TCK transitively pulls **ShrinkWrap Maven
Resolver 3.3**, which relies on `maven-resolver 1.9` and `maven-model 3.9`.
These versions cannot parse the Vidocq reactor's `Model 4.1.0` POMs —
ShrinkWrap's `ClasspathWorkspaceReader` crashes with
`Bad artifact coordinates ... jar:`.

The chosen solution (identical to `vidocq-servlet-chappe-tck-runner`):
a standalone Maven project in `Model 4.0.0`, with internal dependencies pinned to
the current dev version installed in the local M2 repository.

## Installing TCK artifacts

The official TCK JARs are not on Maven Central. Download them once
from the Eclipse Foundation and then install them locally:

```bash
curl -Lo /tmp/restful-ws-tck.zip \
  https://download.eclipse.org/jakartaee/restful-ws/4.0/jakarta-restful-ws-tck-4.0.0.zip
unzip /tmp/restful-ws-tck.zip -d /tmp/restful-ws-tck

mvn install:install-file \
  -Dfile=/tmp/restful-ws-tck/jakarta-restful-ws-tck/lib/jakarta-restful-ws-tck-4.0.0.jar \
  -DgroupId=jakarta.tck -DartifactId=jakarta-restful-ws-tck -Dversion=4.0.0 -Dpackaging=jar
```

(If the zip structure differs, adjust the paths.)

## Running

From the root of the Vidocq project:

```bash
./run-official-tck-restful-4.0.sh                     # smoke test
./run-official-tck-restful-4.0.sh all                 # full suite
./run-official-tck-restful-4.0.sh -Dtest=SomeTests    # targeted class
```

The script:
1. Installs the Vidocq modules into the local M2 (`mvn install` of the reactor).
2. Moves into `vidocq-core-extensions/vidocq-rest-cassini-tck-runner`.
3. Runs `mvn -Ptck-official verify` (or `-Dtest=` if targeted).

## Harness architecture

| Component | Role |
|---|---|
| `CassiniTestHarness` | Mounts Chappe + Cassini on an ephemeral port from a list of `@Path` classes. |
| `VidocqCassiniDeployableContainer` | Arquillian adapter: scans `WEB-INF/classes/` from the TCK WAR, extracts `@Path` classes, loads them into a harness. |
| `VidocqContainerExtension` | Registers the container via the `LoadableExtension` SPI. |
| `VidocqContainerConfiguration` | Accepts the host via `arquillian.xml`. |
| `arquillian.xml` | Container configuration (default host `127.0.0.1`). |

## Status

- Smoke test `CassiniHarnessSmokeTest`: validates that the harness starts and
  answers a `GET /ping` request.
- Official TCK: **scaffolding in place** (`tck-official` profile). Actual
  execution and milestone-by-milestone iteration start as soon as the TCK
  artifacts are installed locally.
