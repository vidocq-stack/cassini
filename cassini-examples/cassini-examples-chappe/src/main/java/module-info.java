module io.vidocq.cassini.examples.chappe {
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires java.net.http;

    // Ouvert à tous (classpath inclus) : JAX-RS + JSON-B font de la reflection
    // sur les ressources et le model pour la sérialisation.
    opens io.vidocq.cassini.examples.chappe.resource;
    opens io.vidocq.cassini.examples.chappe.model;
}
