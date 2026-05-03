module io.vidocq.cassini.examples.chappe {
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires java.net.http;

    // JAX-RS injection / param resolution : reflection sur ressources.
    opens io.vidocq.cassini.examples.chappe.resource;
    // §R-3 — model n'a PAS besoin d'opens : Champollion résout les records
    // via MethodHandles.publicLookup() + getRecordComponents(). On exporte
    // juste le package pour que Champollion puisse charger la classe.
    exports io.vidocq.cassini.examples.chappe.model;
}
