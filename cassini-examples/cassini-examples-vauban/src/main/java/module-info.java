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

    // JAX-RS injection sur ressources, CDI Vauban sur services.
    opens io.vidocq.cassini.examples.vauban.resource;
    opens io.vidocq.cassini.examples.vauban.service;
    // §R-3 — Champollion résout les records via publicLookup, juste exports.
    exports io.vidocq.cassini.examples.vauban.model;
}
