module io.vidocq.cassini.cdi.vauban {
    requires io.vidocq.cassini.api;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires io.vidocq.vauban.core;

    exports io.vidocq.cassini.cdi.vauban;

    provides io.vidocq.cassini.spi.bean.BeanProvider.Factory
            with io.vidocq.cassini.cdi.vauban.VaubanBeanProviderFactory;

    // Cassini BCE that aligns Vauban with JAX-RS 4.0 spec §11.2.5:
    // @Path/@Provider without a scope -> @RequestScoped / @Dependent by default.
    // Without this provides clause, Vauban (a generic CDI container) ignores
    // JAX-RS classes without a bean-defining annotation and Cassini does not discover them.
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.cassini.cdi.vauban.CassiniScopeExtension;
}
