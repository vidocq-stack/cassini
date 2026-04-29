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
        return ServiceLoader.load(BuilderFactory.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No CassiniStack implementation found. Is cassini-core on the classpath?"))
                .create();
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
