package io.vidocq.cassini.spi.http;

import io.vidocq.cassini.spi.resource.ResourceFactory;
import jakarta.ws.rs.core.Application;
import java.util.ServiceLoader;

/**
 * Facade de bootstrap Cassini — assemble les composants internes (router,
 * invoker, registries) et expose un {@link CassiniHttpAdapter} prêt à dispatcher.
 *
 * <p>Permet à {@code cassini-chappe}, {@code cassini-jdk-http} et {@code cassini-cdi}
 * de ne plus dépendre de {@code cassini-core} directement : ils passent par la SPI
 * publique {@code cassini-api}.
 *
 * <p>Exemple d'usage :
 * <pre>{@code
 * CassiniStack stack = CassiniStack.builder()
 *         .application(new MyApplication())
 *         .build();
 * CassiniHttpAdapter adapter = stack.adapter();
 * }</pre>
 */
public interface CassiniStack {

    /** L'adapter HTTP prêt à dispatcher des requêtes. */
    CassiniHttpAdapter adapter();

    static Builder builder() {
        // Primary: load via cassini-core's own classloader using reflection.
        // This works because cassini-core is on the module path but may not be
        // transitively readable from the calling module.
        try {
            Class<?> factoryClass = Class.forName(
                    "io.vidocq.cassini.internal.CassiniStackBuilderFactory",
                    true,
                    // Use cassini-api's classloader — in JPMS named modules, all
                    // classes on the module path share the same bootstrap classloader,
                    // so Class.forName() from any module can find any named module's class
                    // as long as we use the application classloader (not the boot CL).
                    Thread.currentThread().getContextClassLoader() != null
                            ? Thread.currentThread().getContextClassLoader()
                            : CassiniStack.class.getClassLoader());
            BuilderFactory factory = (BuilderFactory) factoryClass
                    .getDeclaredConstructor().newInstance();
            return factory.create();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "No CassiniStack implementation found. Is cassini-core on the classpath?", e);
        }
    }

    interface Builder {
        Builder application(Application app);
        Builder resourceFactory(ResourceFactory factory);

        /** Ajoute une instance {@code @Provider} enregistrée manuellement. */
        Builder provider(Object providerInstance);

        CassiniStack build();
    }

    /** SPI — implémentée par cassini-core via ServiceLoader. */
    interface BuilderFactory {
        Builder create();
    }
}
