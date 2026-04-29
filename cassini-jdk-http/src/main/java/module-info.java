module io.vidocq.cassini.jdkhttp {
    requires io.vidocq.cassini.api;
    requires jdk.httpserver;
    requires static jakarta.ws.rs;

    exports io.vidocq.cassini.jdkhttp;
}
