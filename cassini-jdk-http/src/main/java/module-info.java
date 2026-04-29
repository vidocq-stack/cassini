module io.vidocq.cassini.jdkhttp {
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.core;  // dans le module graph — ServiceLoader CassiniStack.BuilderFactory
    requires jdk.httpserver;
    requires static jakarta.ws.rs;

    exports io.vidocq.cassini.jdkhttp;
}
