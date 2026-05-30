module io.vidocq.cassini.examples.jdkhttp {
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.jdkhttp;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires java.net.http;
    requires jdk.httpserver;

    // JAX-RS injection / param resolution: reflection on resources.
    opens io.vidocq.cassini.examples.jdkhttp.resource;
    // §R-3 — Champollion resolves records via publicLookup, just exports.
    exports io.vidocq.cassini.examples.jdkhttp.model;
}
