module io.vidocq.cassini.core {
    requires transitive io.vidocq.cassini.api;
    requires transitive jakarta.ws.rs;
    requires static jakarta.annotation;

    requires static jakarta.xml.bind;
    requires static jakarta.activation;
    requires jakarta.json;
    requires jakarta.json.bind;
    requires org.eclipse.yasson;
    requires org.eclipse.parsson;

    requires static java.net.http;
    requires java.xml;

    exports io.vidocq.cassini;
    exports io.vidocq.cassini.internal
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.cdi,
               io.vidocq.cassini.chappe,
               io.vidocq.cassini.jdkhttp;
}
