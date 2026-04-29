module io.vidocq.cassini.api {
    requires transitive jakarta.ws.rs;
    requires static jakarta.annotation;

    exports io.vidocq.cassini.spi.http;
    exports io.vidocq.cassini.spi.resource;
    exports io.vidocq.cassini.spi.bean;

    uses io.vidocq.cassini.spi.http.CassiniStack.BuilderFactory;
    uses io.vidocq.cassini.spi.bean.BeanProvider.Factory;
}
