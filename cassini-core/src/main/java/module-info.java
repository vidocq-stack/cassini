module io.vidocq.cassini.core {
    requires transitive io.vidocq.cassini.api;
    requires transitive jakarta.ws.rs;
    requires static jakarta.annotation;
    requires static jakarta.cdi;
    requires static jakarta.inject;

    requires static jakarta.xml.bind;
    requires static jakarta.activation;
    requires jakarta.json;
    requires jakarta.json.bind;
    requires org.eclipse.yasson;
    requires org.eclipse.parsson;

    requires static java.net.http;
    requires java.xml;

    exports io.vidocq.cassini.internal
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.cdi;
    exports io.vidocq.cassini.internal.context
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.cdi;
    exports io.vidocq.cassini.internal.filter
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.cdi;
    exports io.vidocq.cassini.internal.multipart
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.runtime
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.transport
            to io.vidocq.cassini.tck;

    opens io.vidocq.cassini.internal to io.vidocq.cassini.cdi;
    opens io.vidocq.cassini.internal.runtime to io.vidocq.cassini.chappe, io.vidocq.cassini.jdkhttp;

    provides io.vidocq.cassini.spi.http.CassiniStack.BuilderFactory
            with io.vidocq.cassini.internal.CassiniStackBuilderFactory;

    // jakarta.ws.rs.ext.RuntimeDelegate fourni par cassini-chappe ou
    // cassini-jdk-http (transport-spécifique pour SeBootstrap). Cassini-core
    // n'expose pas son CassiniRuntimeDelegate par ServiceLoader pour éviter
    // la collision : c'est le rôle de l'adapter de transport actif.
}
