# Rapport de développement assisté par IA — Cassini

> Retour d'expérience sur la construction de Cassini avec Claude Code (Sonnet 4.6 / Opus 4.7).
> Durée réelle : **~10 jours** (du 2026-04-20 au 2026-04-30).
> Développeur : 1 senior (25 ans d'expérience Java/Jakarta EE).

---

## Ce qui a été construit

| Module | Rôle |
|--------|------|
| `cassini-api` | SPI publique (CassiniHttpExchange, CassiniHttpAdapter, ResourceFactory, BeanProvider, CassiniStack) — zéro dépendance hors `jakarta.ws.rs-api` |
| `cassini-core` | Implémentation JAX-RS 4.0 complète : Invoker, ResourceScanner, UriRouter, MessageBodyRegistry, filters, interceptors, multipart, SSE, providers built-in |
| `cassini-chappe` | Adapter transport Chappe + `ChappeRuntimeDelegate` (SeBootstrap), bridge HTTP/1.1 + HTTP/2 |
| `cassini-jdk-http` | Adapter transport JDK natif (`com.sun.net.httpserver`) zéro-dép externe |
| `cassini-cdi-vauban` | Adapter CDI Vauban (`VaubanBeanProvider` via SPI) + `CassiniScopeExtension` (BCE) |
| `cassini-tck` | Runner Arquillian + harness officiel Jakarta REST 4.0 (Model 4.0.0 standalone pour contournement ShrinkWrap) |
| `cassini-examples` × 3 | Exemples chappe / jdkhttp / vauban (avec UI HTML/CSS/JS et handler composite Chappe) |

**Caractéristiques transversales**

- **JPMS natif** — chaque module a son `module-info.java`, packages internes verrouillés via `exports ... to`
- **Transport-agnostique** — `cassini-core` n'importe ni `chappe` ni `httpserver` ; SPI publique permet à n'importe quel transport (Netty, Undertow, Vert.x) de s'intégrer
- **CDI-pluggable** — SPI `BeanProvider` découplée de `jakarta.cdi` ; un adapter pour chaque container (Vauban fourni, Weld/OpenWebBeans futurs)
- **Virtual threads** — VT par requête (`Executors.newVirtualThreadPerTaskExecutor`), `@Suspended AsyncResponse` et `CompletionStage<T>` bloquent un VT sans starvation
- **TCK officiel Jakarta REST 4.0.1 — `2535/2535` (100 %)** des tests applicables au profil Core Profile / SE-Bootstrap

---

## Métriques quantitatives

| Métrique | Valeur |
|---|---|
| Période | 10 jours calendaires |
| Commits | **168** (sur le repo origine `vidocq/`) + **24** (post-extraction) = 192 |
| Code Java | ~14 200 lignes (hors generated/target) |
| Tests TCK exécutés | 2670 (134 hors-profil + 6 challenges = 135 skipped) |
| TCK PASS | **2535/2535** applicables (100 %) |
| Tests unitaires Cassini | 34 (cassini-core) |
| Tests examples | 30 (10 chappe + 10 jdkhttp + 10 vauban) |
| Modules | 7 (api, core, chappe, jdk-http, cdi-vauban, tck, examples × 3) |

---

## Estimation sans IA

### Développeur seul (profil senior, 25 ans d'expérience)

| Bloc | Durée estimée |
|------|--------------|
| §3 Resources (URI templates, best-match §3.7.2, sub-resource locators récursifs §3.4) | 1,5 mois |
| §4 Providers (MBR/MBW selection, ExceptionMapper §4.4, ContextResolver, JSON-B/Yasson, JAXB) | 1,5 mois |
| §5 Context (Request, UriInfo, Variant, HttpHeaders) | 1 mois |
| §6 Filters / Interceptors / DynamicFeature §6.5.5 + §6.7.4 setEntityStream | 1,5 mois |
| §8 @Suspended AsyncResponse + §9 CompletionStage<T> | 1 mois |
| §10 Application/ApplicationPath + SeBootstrap (RuntimeDelegate complet) | 1 mois |
| §11 SSE serveur (CassiniSseEventSink, SseBroadcaster, OutboundSseEvent, EventSource) | 1 mois |
| §3.5.4 EntityPart + Multipart RFC 7578 (MBR/MBW + Builder) | 1 mois |
| §11.2 BASIC auth + SecurityContext | 0,5 mois |
| Transport découplé (`CassiniHttpExchange` + adapters Chappe et JDK) | 1 mois |
| Intégration CDI (BeanManager, ScopeExtension, BCE) | 1 mois |
| TCK setup (Arquillian + ShrinkWrap + contournement Model 4.0.0) | 0,5 mois |
| TCK 100 % — debug des 2535 tests applicables | 2,5 mois |
| SPI propre (`CassiniStack`, `BeanProvider`, ServiceLoader, classpath + JPMS) | 0,5 mois |
| Examples + UI HTML/CSS/JS + handler composite | 0,5 mois |
| Documentation (TCK.md, ASYNC.md, README × 4, HOWTO-CLAUDE.md) | 0,5 mois |
| Friction JPMS transversale | +30 % sur l'ensemble |
| **Total** | **~18–22 mois** |

> Le TCK seul (2,5 mois) est la phase la plus chronophage. Chaque corner case (sub-resource locator récursif, Cookie multi-attribut RFC 2109, content-type avec `;charset=` non standardisé, status-code de variants ambigus, header lookup case-insensitive…) peut représenter une demi-journée de debug. Atteindre 100 % et pas 95 % coûte disproportionnément cher.

### Équipe de 2 seniors

Environ **10–13 mois** — la coordination (revues PR, alignement architectural, propriété du code partagée) limite le gain linéaire. Le TCK reste la phase la moins parallélisable car les fixes y sont souvent interdépendants (un fix sur la résolution de `@Path` affecte 50+ tests).

---

## Facteur d'accélération

```
~20x
```

10 jours pilotés ≈ 18–22 mois solo.

Plus précisément :
- **Phase initiale (M1–M2c, scaffolding + routing + injection + MBR/MBW)** : ratio le plus haut (~30x) — code structurellement similaire à des patterns connus, l'IA produit du Java idiomatique sans hésitation.
- **Phase TCK (94 % → 100 %)** : ratio plus bas (~10–12x) — chaque échec demande lecture spec + design fix + validation, le pilotage humain redevient dominant.
- **Refactorings architecturaux (CassiniStack SPI, BeanProvider SPI)** : ratio moyen (~15x) — l'IA exécute brillamment une fois le design tranché, mais ne tranche pas elle-même.

---

## Ce que l'IA a apporté

### Spec JAX-RS 4.0 disponible en mémoire de travail

Les règles de §3.7.2 (best-match avec literals + capture groups + path remaining), §4.2.4 (sélection MBR avec `Object`/byte[]/InputStream), §6.5.2 (NameBinding sur sous-classe Application), §3.4 (sub-resource locator récursif avec dispatch dynamique sur `Class<T>`) — toutes ces règles ont été appliquées correctement dès le premier jet, sans aller-retour avec la spec PDF.

### Boilerplate JAX-RS

Implémenter `UriBuilder`, `Response.ResponseBuilder`, `HeaderDelegate<T>` pour 8 types JAX-RS, les 5 contextes d'intercepteurs (RequestContext, ResponseContext, ReaderInterceptorContext, WriterInterceptorContext, DynamicFeatureContext) — c'est plusieurs semaines de boilerplate qui passent à quelques heures.

### TCK pattern recognition

Les erreurs TCK ont des familles. Une fois qu'un fix sur "header lookup case-insensitive §6.7.4" est trouvé, l'IA reconnaît les 3 autres tests qui échouaient pour la même raison sans qu'il soit nécessaire de chercher. Sur 2535 tests, ce pattern recognition a probablement économisé 20+ jours.

### Refactorings cross-module

Renommer `cassini-cdi` → `cassini-cdi-vauban` impacte : artifactId Maven, module Java, package Java sur tous les fichiers, `requires` dans 4 module-info, exports dans cassini-core, dépendance dans 1 example, ServiceLoader entry, documentation. L'IA propage tout en ~5 minutes, là où un humain aurait passé ~1h avec risques d'oublis.

### Documentation à la volée

`TCK.md`, `ASYNC.md`, `AI_DEV.md`, README par module, schémas ASCII, justifications des challenges TCK Process 1.4.1 — produits en parallèle du code, sans coût marginal sur la concentration architecturale.

---

## Ce que l'IA n'a pas remplacé

- **Vision architecturale** — la décision de séparer `cassini-api` (SPI publique) de `cassini-core` (impl), de rendre le transport agnostique via `CassiniHttpExchange`, de promouvoir `CassiniStack.builder()` comme façade publique pour les transports tiers. L'IA exécute le design, ne le crée pas.
- **Détection des odeurs architecturales** — c'est moi qui ai dit "non, je ne veux pas spécifier les singletons à la main" pour faire émerger le `BeanProvider`. L'IA avait livré un kludge `getSingletons()` techniquement correct mais médiocre.
- **Intuition du domaine** — savoir que le TCK Multipart `basicTest` échoue côté Jersey CLIENT et pas côté Cassini SERVER (c'est un challenge à documenter, pas un bug à fixer). Savoir que `locatorNameTooLongAgainTest` impose une interprétation segment-par-segment non-portable de §3.7.2.
- **Découverte d'API existantes** — l'IA n'est pas allée fouiller `chappe-api/StaticFileHandler` ni `vauban-maven-plugin:generate` toute seule. C'est moi qui ai pointé "Chappe a déjà ça", "Vauban a un APT". Sans cette intervention, les exemples auraient eu du custom code inférieur.
- **Décisions produit** — périmètre Core Profile vs Full, choix des challenges TCK officiels à documenter (vs essayer de fixer), priorité au JPMS strict dès le départ.

---

## Observations sur la méthode de travail

### Ce qui a bien fonctionné

- **Plan mode systématique** sur les refactorings (CassiniStack SPI, BeanProvider SPI) — rédiger le plan en 5 étapes avant de toucher au code a évité les zigzags coûteux.
- **Agents parallèles isolés (worktree)** sur les gros refactorings cross-module — pendant qu'un agent restructurait `cassini-chappe`, je continuais à analyser le diff et préparer la mise à jour de `cassini-jdk-http` mentalement.
- **Context-mode** pour les sorties TCK (~37 000 lignes par run) — l'externalisation a évité de saturer la fenêtre de contexte sur les sessions longues.
- **Validation TCK après chaque fix** — coût ~2 min par run mais évite les régressions silencieuses qui auraient coûté beaucoup plus cher en aval.
- **Revue diff systématique** avant chaque commit — l'IA produit parfois du code qui compile et passe les tests mais introduit des régressions architecturales (duplication, perte d'encapsulation).

### Ce qui a coûté du temps malgré l'IA

- **Friction JPMS** récurrente — `opens to named-module` qui ne couvre pas l'unnamed module, `requires` manquants en mode classpath, `ServiceLoader` qui ignore le `provides` quand le module n'est pas en graph, `META-INF/services` à doublonner pour le mode classpath. Probablement 1 jour cumulé.
- **Choix d'auto-discovery vs explicite** — premier jet `getSingletons()` (kludge), deuxième jet `BeanProvider` (correct). ~30 min de re-travail.
- **Bugs latents introduits par l'agent en autonomie** — duplication de 700 lignes de boilerplate `RuntimeDelegate` au lieu d'extension, oubli de `META-INF/services` qui a fait échouer le TCK une fois (7 errors `SeBootstrapIT`). ~1h cumulée.
- **Zombie process port 8080** sur poste de dev — bloqué une heure pendant la première validation TCK avant identification du conflit.

---

## Comparaison aux implémentations de référence

| Implementation | Équipe | Durée publique connue |
|---|---|---|
| Jersey (Eclipse Foundation) | Multi-personnes Oracle/Eclipse | Années (depuis 2010+) |
| RESTEasy (Red Hat) | Multi-personnes | Années (depuis 2007+) |
| Apache CXF (rs) | Multi-personnes ASF | Années |
| **Cassini** | **1 senior + IA** | **10 jours pour 2535/2535** |

Note : ces implémentations couvrent un périmètre plus large que Cassini (notamment client API + Servlet + EE Full Profile). Mais sur le périmètre Core Profile / SE-Bootstrap qui constitue Cassini, atteindre la conformance officielle TCK 100 % en moins de 2 semaines est sans précédent à ma connaissance.

---

## Conclusion

Pour un projet de cette densité technique — spec formelle (JAX-RS 4.0 = 200+ pages denses), TCK officiel à 100 %, JPMS natif, transport-agnostique, CDI-pluggable, virtual threads — l'assistance IA a représenté un multiplicateur de **~20x** sur la vitesse de développement.

Le gain n'est pas uniforme : il est maximal sur le code mécanique (boilerplate JAX-RS, refactorings cross-module, génération de tests) et nul sur les décisions d'architecture, le jugement TCK challenges vs bugs, et l'intuition produit.

Comme pour Vauban, le modèle le plus juste n'est pas "l'IA code à la place du développeur" mais
**"le développeur senior pilote à la vitesse de sa pensée plutôt qu'à la vitesse de sa frappe"**.

La friction restante n'est plus dans la production de code, c'est dans :
1. La supervision de l'agent (relire chaque diff, corriger les choix sub-optimaux)
2. La friction toolchain (JPMS, IntelliJ test runner, Maven 4 vs IDE LSP)
3. La validation TCK (la spec ne se simplifie pas avec l'IA)

Si je devais refaire Cassini sans IA aujourd'hui, je n'essaierais probablement pas seul.
Avec deux seniors, j'estimerais **10 à 13 mois calendaires** pour atteindre TCK 2535/2535
sur le même périmètre. Avec IA et un seul senior aux commandes, ça a été **10 jours**.
