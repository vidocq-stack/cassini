module io.vidocq.cassini.examples.vauban {
    requires java.net.http;
    requires jakarta.ws.rs;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.json.bind;
    requires io.vidocq.vauban.core;
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.chappe;
    requires io.vidocq.cassini.cdi.vauban;
    requires io.vidocq.chappe.api;

    // Ravel MicroProfile Config (optional but present in this example)
    requires io.vidocq.ravel.api;
    requires io.vidocq.ravel.cdi.vauban;

    // ZERO opens AND zero exports on the resource/service packages — a full Mode-B (CDI) zero-export
    // app. Both the Cassini dispatch path and the Vauban proxy path now run in-module:
    //  • cassini-core obtains each <Resource>$$CassiniAdapter / $$CassiniRoutes through ServiceLoader
    //    (the `provides` below); the module system instantiates them from the closed package, so
    //    cassini-core never reflects into it (dispatch + @*Param coercion run in the adapter).
    //  • vauban-core creates the @ApplicationScoped beans' <Bean>_ClientProxy in-module via the
    //    per-package _VaubanComponents provider (createClientProxy → `new <Bean>_ClientProxy()`), and
    //    field-injects @Inject members in-package (package-private putfield) — no opens, no exports.
    // R-3 — only the model package stays exported (Champollion resolves records via publicLookup).
    exports io.vidocq.cassini.examples.vauban.model;

    // Cassini dispatch metadata as ServiceLoader providers (keeps the resource package closed).
    provides io.vidocq.cassini.spi.gen.ResourceAdapter
            with io.vidocq.cassini.examples.vauban.resource.GreetingResource$$CassiniAdapter,
                 io.vidocq.cassini.examples.vauban.resource.TodoResource$$CassiniAdapter,
                 io.vidocq.cassini.examples.vauban.resource.ConfigDemoResource$$CassiniAdapter;
    provides io.vidocq.cassini.spi.gen.RouteProvider
            with io.vidocq.cassini.examples.vauban.resource.GreetingResource$$CassiniRoutes,
                 io.vidocq.cassini.examples.vauban.resource.TodoResource$$CassiniRoutes,
                 io.vidocq.cassini.examples.vauban.resource.ConfigDemoResource$$CassiniRoutes;

    // Vauban instantiates the @ApplicationScoped beans, their client proxies, and field-injects them
    // in-module through the APT-generated per-package _VaubanComponents providers.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.cassini.examples.vauban.resource._VaubanComponents,
                 io.vidocq.cassini.examples.vauban.service._VaubanComponents;
}
