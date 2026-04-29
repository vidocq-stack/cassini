module io.vidocq.cassini.cdi.vauban {
    requires io.vidocq.cassini.api;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires io.vidocq.vauban.core;

    exports io.vidocq.cassini.cdi.vauban;

    provides io.vidocq.cassini.spi.bean.BeanProvider.Factory
            with io.vidocq.cassini.cdi.vauban.VaubanBeanProviderFactory;
}
