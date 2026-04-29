module io.vidocq.cassini.examples.jdkhttp {
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.jdkhttp;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires java.net.http;
    requires jdk.httpserver;

    // Ouvert à tous : JAX-RS + JSON-B font de la reflection sur ressources et model.
    opens io.vidocq.cassini.examples.jdkhttp.resource;
    opens io.vidocq.cassini.examples.jdkhttp.model;
}
