module io.vidocq.cassini.cdi.vauban {
    requires io.vidocq.cassini.api;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires io.vidocq.vauban.core;

    exports io.vidocq.cassini.cdi.vauban;

    provides io.vidocq.cassini.spi.bean.BeanProvider.Factory
            with io.vidocq.cassini.cdi.vauban.VaubanBeanProviderFactory;

    // BCE Cassini qui aligne Vauban sur la spec JAX-RS 4.0 §11.2.5 :
    // @Path/@Provider sans scope -> @RequestScoped / @Dependent par defaut.
    // Sans ce provides, Vauban (container CDI generique) ignore les classes
    // JAX-RS sans annotation bean-defining et Cassini ne les decouvre pas.
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.cassini.cdi.vauban.CassiniScopeExtension;
}
