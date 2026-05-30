# vidocq-rest-cassini-extension

**Jakarta RESTful Web Services 4.0** implementation for Vidocq, built on the
in-house HTTP engine **Chappe** (via `ChappeMountPoint`). 

## Cassini — where does the name come from?

The **Cassini** dynasty (1625-1845, four generations) produced the
**first complete map of France through geodetic triangulation** — every
point in the kingdom connected to a hierarchical network of triangles, then to
roads, then to named places.

That is exactly what a JAX-RS router does:

| Cassini (mapping) | Cassini (runtime) |
|---|---|
| Geodetic triangles | URI templates `@Path("/a/{b}/c")` |
| Precision surveying | Exact match vs regex (`{id:\\d+}`) |
| Hierarchical mesh France → province → city | Root resource → sub-resource locator → sub-resource method |
| Reference meridian | `UriInfo.getBaseUri()` |
| Triangulation: unique point from 3 angles | Best match: unique method by (path, verb, media-type) |

The REST resource tree is a **map**. Cassini is the runtime that draws it and
routes each request through it — and it does so on the signaling lines drawn by
Chappe (the optical telegraph, HTTP transport).

## Status

| Milestone | Content | Status |
|---|---|---|
| Phase 0 | Module skeleton, POM, BCE `@RequestScoped` | Done |
| M1 | `ChappeMountPoint` bridge + minimal routing | Planned |
| M2a..j | JAX-RS core (URI, params, body, @Context, Response, Filters, Features, Async, SSE, CDI) | Planned |
| M3 | Jakarta REST 4.0 TCK | Planned |

## Configuration

| Property | Default | Description |
|---|---|---|
| `vidocq.rest.context-path` | `/` | JAX-RS mount prefix |
| `vidocq.rest.listener` | `default` | Target Chappe listener |
