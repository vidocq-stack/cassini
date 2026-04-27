/**
 * Module de harness TCK pour l'extension Cassini (JAX-RS 4.0 sur Chappe).
 *
 * <p>Module <b>hors reactor</b> — utilise Maven Model 4.0.0 pour
 * compatibilité ShrinkWrap (dép. transitive du TCK Jakarta).</p>
 */
module io.vidocq.cassini.tck {
    requires io.vidocq.cassini;
    requires fr.vidocq.chappe.api;
    requires jakarta.ws.rs;

    requires static java.net.http;

    exports io.vidocq.cassini.tck;
}
