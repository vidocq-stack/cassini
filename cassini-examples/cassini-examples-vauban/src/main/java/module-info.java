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

    // JAX-RS injection on resources, CDI Vauban on services.
    opens io.vidocq.cassini.examples.vauban.resource;
    opens io.vidocq.cassini.examples.vauban.service;
    // R-3 — Champollion resolves records via publicLookup, just exports.
    exports io.vidocq.cassini.examples.vauban.model;
}
