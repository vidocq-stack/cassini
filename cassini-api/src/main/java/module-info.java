module io.vidocq.cassini.api {
    requires transitive jakarta.ws.rs;
    requires static jakarta.annotation;

    exports io.vidocq.cassini.spi.http;
    exports io.vidocq.cassini.spi.resource;
}
