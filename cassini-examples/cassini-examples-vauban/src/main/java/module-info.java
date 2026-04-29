module io.vidocq.cassini.examples.vauban {
    requires java.net.http;
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.core;
    requires io.vidocq.cassini.chappe;
    requires io.vidocq.cassini.cdi;
    requires io.vidocq.chappe.api;
    requires jakarta.ws.rs;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.json.bind;
    requires io.vidocq.vauban.core;

    opens io.vidocq.cassini.examples.vauban.resource;
    opens io.vidocq.cassini.examples.vauban.service;
    opens io.vidocq.cassini.examples.vauban.model;
}
