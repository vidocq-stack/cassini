module io.vidocq.cassini.examples.vauban {
    requires io.vidocq.cassini.core;
    requires io.vidocq.cassini.chappe;
    requires io.vidocq.cassini.cdi;
    requires io.vidocq.vauban.core;
    requires io.vidocq.chappe.api;
    requires jakarta.ws.rs;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.json.bind;

    // Ouverture pour la reflection JAX-RS (ResourceScanner, Invoker) et CDI
    opens io.vidocq.cassini.examples.vauban.resource to io.vidocq.cassini.chappe, io.vidocq.cassini.core, io.vidocq.vauban.core;
    opens io.vidocq.cassini.examples.vauban.service to io.vidocq.vauban.core, io.vidocq.cassini.core;
    // Ouverture complète du model pour JSON-B (yasson utilise reflection sur les records)
    opens io.vidocq.cassini.examples.vauban.model;
}
