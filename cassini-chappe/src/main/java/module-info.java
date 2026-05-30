module io.vidocq.cassini.chappe {
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.core;  // in the module graph — ServiceLoader CassiniStack.BuilderFactory
    requires io.vidocq.chappe.api;
    requires jakarta.ws.rs;
    requires static jakarta.cdi;

    exports io.vidocq.cassini.chappe;

    provides jakarta.ws.rs.ext.RuntimeDelegate
            with io.vidocq.cassini.chappe.ChappeRuntimeDelegate;
}
