/**
 * TCK harness module for the Cassini extension (JAX-RS 4.0 on Chappe).
 *
 * <p><b>Out-of-reactor</b> module — uses Maven Model 4.0.0 for
 * ShrinkWrap compatibility (transitive dependency of the Jakarta TCK).</p>
 */
module io.vidocq.cassini.tck {
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.core;
    requires io.vidocq.cassini.chappe;
    requires io.vidocq.chappe.api;
    requires jakarta.ws.rs;
    requires jakarta.cdi;

    requires static java.net.http;

    exports io.vidocq.cassini.tck;
}
