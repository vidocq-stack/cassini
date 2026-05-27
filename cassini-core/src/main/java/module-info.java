module io.vidocq.cassini.core {
    requires transitive io.vidocq.cassini.api;
    requires transitive jakarta.ws.rs;
    requires static jakarta.annotation;
    requires static jakarta.inject;

    requires static jakarta.xml.bind;
    requires static jakarta.activation;
    requires jakarta.json;
    requires jakarta.json.bind;
    requires io.vidocq.champollion.jsonp;
    requires io.vidocq.champollion.jsonb;

    requires static java.net.http;
    requires java.xml;
    requires java.logging;

    exports io.vidocq.cassini.internal
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.client,
               io.vidocq.cassini.maven.plugin;
    exports io.vidocq.cassini.internal.gen
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.maven.plugin;
    exports io.vidocq.cassini.internal.context
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.filter
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.multipart
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.runtime
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.jdkhttp,
               io.vidocq.cassini.client;
    exports io.vidocq.cassini.internal.transport
            to io.vidocq.cassini.tck;

    opens io.vidocq.cassini.internal.runtime to io.vidocq.cassini.chappe, io.vidocq.cassini.jdkhttp, io.vidocq.cassini.client;

    provides io.vidocq.cassini.spi.http.CassiniStack.BuilderFactory
            with io.vidocq.cassini.internal.CassiniStackBuilderFactory;

    // jakarta.ws.rs.ext.RuntimeDelegate fourni par cassini-chappe ou
    // cassini-jdk-http (transport-spécifique pour SeBootstrap). Cassini-core
    // n'expose pas son CassiniRuntimeDelegate par ServiceLoader pour éviter
    // la collision : c'est le rôle de l'adapter de transport actif.
}
