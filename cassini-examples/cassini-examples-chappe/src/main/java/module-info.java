module io.vidocq.cassini.examples.chappe {
    requires jakarta.ws.rs;
    requires jakarta.json.bind;

    // Ouverture pour la reflection JAX-RS (ResourceScanner, Invoker)
    opens io.vidocq.cassini.examples.chappe.resource to io.vidocq.cassini.chappe, io.vidocq.cassini.core;
    // Ouverture complète du model pour JSON-B (yasson utilise reflection sur les records)
    opens io.vidocq.cassini.examples.chappe.model;
}
