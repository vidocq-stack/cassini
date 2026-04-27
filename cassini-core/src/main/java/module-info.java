import io.vidocq.cassini.CassiniExtension;
import io.vidocq.cassini.CassiniScopeBCE;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;

module io.vidocq.cassini {
    requires transitive io.vidocq.mpserver.spi;
    requires io.vidocq.mpserver.ext.chappe;
    requires io.vidocq.vauban.core;
    requires fr.vidocq.chappe.api;

    requires transitive jakarta.ws.rs;
    requires jakarta.cdi;
    requires jakarta.annotation;

    requires static java.net.http;
    requires java.xml;
    requires static jakarta.xml.bind;
    requires static jakarta.activation;

    exports io.vidocq.cassini;
    exports io.vidocq.cassini.internal
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.filter
            to io.vidocq.cassini.tck;

    opens io.vidocq.cassini to io.vidocq.vauban.core;

    provides io.vidocq.mpserver.spi.VidocqExtension
            with CassiniExtension;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with CassiniScopeBCE;

    provides jakarta.ws.rs.ext.RuntimeDelegate
            with CassiniRuntimeDelegate;
}
