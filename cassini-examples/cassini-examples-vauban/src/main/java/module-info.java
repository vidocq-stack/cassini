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

    // Zero opens. cassini.core instantiates the build-time generated <Resource>$$CassiniAdapter /
    // $$CassiniRoutes (public, in this package) via Class.forName + public reflection — it needs an
    // EXPORT, not an opens (same pattern as knock-jaxrs/cervantes-jaxrs in production). With the
    // adapter present, dispatch and @*Param coercion run in-package; no reflective setAccessible on
    // the resource methods.
    exports io.vidocq.cassini.examples.vauban.resource;
    // Exported (not opened): vauban.core instantiates the @ApplicationScoped TodoService's generated
    // public TodoService_ClientProxy via public reflection — an export suffices, no opens needed (same
    // mechanism as the resource package above and as knock-jaxrs/cervantes-jaxrs in production).
    exports io.vidocq.cassini.examples.vauban.service;
    // R-3 — Champollion resolves records via publicLookup, just exports.
    exports io.vidocq.cassini.examples.vauban.model;

    // Vauban instantiates the @ApplicationScoped resources and service AND field-injects them
    // in-module through the APT-generated per-package _VaubanComponents providers (their @Inject
    // fields are package-private — in-package putfield), so vauban.core needs no opens into these
    // packages.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.cassini.examples.vauban.resource._VaubanComponents,
                 io.vidocq.cassini.examples.vauban.service._VaubanComponents;
}
