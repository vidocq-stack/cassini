module io.vidocq.cassini.examples.vauban {
    requires java.net.http;
    requires jakarta.ws.rs;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.json.bind;
    requires io.vidocq.vauban.core;

    // Ouvert à tous : JAX-RS + JSON-B font de la reflection sur ressources,
    // service et model pour injection et sérialisation.
    opens io.vidocq.cassini.examples.vauban.resource;
    opens io.vidocq.cassini.examples.vauban.service;
    opens io.vidocq.cassini.examples.vauban.model;
}
