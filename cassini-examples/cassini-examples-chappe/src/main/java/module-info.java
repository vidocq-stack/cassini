module io.vidocq.cassini.examples.chappe {
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires java.net.http;

    // JAX-RS injection / param resolution: reflection on resources.
    opens io.vidocq.cassini.examples.chappe.resource;
    // §R-3 — model does NOT need opens: Champollion resolves records
    // via MethodHandles.publicLookup() + getRecordComponents(). We just export
    // the package so Champollion can load the class.
    exports io.vidocq.cassini.examples.chappe.model;
}
