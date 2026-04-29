module io.vidocq.cassini.cdi {
    requires io.vidocq.cassini.api;
    requires jakarta.cdi;
    requires jakarta.ws.rs;

    exports io.vidocq.cassini.cdi;

    provides io.vidocq.cassini.spi.resource.ResourceFactory
            with io.vidocq.cassini.cdi.CdiResourceFactory;
}
