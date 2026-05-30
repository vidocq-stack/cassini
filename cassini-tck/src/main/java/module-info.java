/**
 * TCK harness module for the Cassini extension (JAX-RS 4.0 on Chappe).
 *
 * <p>Module <b>out of reactor</b> — uses Maven Model 4.0.0 for
 * ShrinkWrap compatibility (transitive dep from the Jakarta TCK).</p>
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
