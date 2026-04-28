# vidocq-rest-cassini-extension

Implémentation **Jakarta RESTful Web Services 4.0** pour Vidocq, montée sur le
moteur HTTP maison **Chappe** (via `ChappeMountPoint`). 

## Cassini — d'où vient le nom ?

La dynastie **Cassini** (1625-1845, quatre générations) a dressé la
**première carte complète de France par triangulation géodésique** — chaque
point du royaume rattaché à un réseau hiérarchique de triangles, puis à des
chemins, puis à des lieux nommés.

C'est exactement ce que fait un routeur JAX-RS :

| Cassini (cartographie) | Cassini (runtime) |
|---|---|
| Triangles géodésiques | URI templates `@Path("/a/{b}/c")` |
| Toisés de précision | Match exact vs regex (`{id:\\d+}`) |
| Mesh hiérarchique France → province → ville | Root resource → sub-resource locator → sub-resource method |
| Méridien de référence | `UriInfo.getBaseUri()` |
| Triangulation : point unique par 3 angles | Best-match : méthode unique par (path, verb, media-type) |

L'arborescence des ressources REST est une **carte**. Cassini est le runtime
qui la dresse et y route chaque requête — et il le fait sur les lignes de
signalisation tracées par Chappe (le télégraphe optique, transport HTTP).

## Statut

| Jalon | Contenu | État |
|---|---|---|
| Phase 0 | Squelette module, POM, BCE `@RequestScoped` | Fait |
| M1 | Bridge `ChappeMountPoint` + routing minimal | Prévu |
| M2a..j | Core JAX-RS (URI, params, body, @Context, Response, Filters, Features, Async, SSE, CDI) | Prévu |
| M3 | TCK Jakarta REST 4.0 | Prévu |

## Configuration

| Propriété | Défaut | Description |
|---|---|---|
| `vidocq.rest.context-path` | `/` | Préfixe de montage JAX-RS |
| `vidocq.rest.listener` | `default` | Listener Chappe cible |
