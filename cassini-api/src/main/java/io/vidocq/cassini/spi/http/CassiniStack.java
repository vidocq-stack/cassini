package io.vidocq.cassini.spi.http;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cassini.spi.resource.ResourceFactory;
import jakarta.ws.rs.core.Application;

import java.util.Comparator;
import java.util.ServiceLoader;

/**
 * Facade de bootstrap Cassini — assemble les composants internes (router,
 * invoker, registries) et expose un {@link CassiniHttpAdapter} prêt à dispatcher.
 *
 * <p>Permet à {@code cassini-chappe}, {@code cassini-jdk-http} et aux modules
 * d'intégration DI (ex. {@code cassini-cdi-vauban}) de ne plus dépendre de
 * {@code cassini-core} directement : ils passent par la SPI publique
 * {@code cassini-api}.
 *
 * <p>Exemple d'usage :
 * <pre>{@code
 * CassiniStack stack = CassiniStack.builder()
 *         .application(new MyApplication())
 *         .build();
 * CassiniHttpAdapter adapter = stack.adapter();
 * }</pre>
 *
 * <p>Si un {@link BeanProvider.Factory} est enregistré via {@link ServiceLoader}
 * (ex. {@code cassini-cdi-vauban} sur le classpath), le {@link Builder} est
 * automatiquement préconfiguré avec le {@link BeanProvider} de plus haute
 * priorité. L'utilisateur peut neutraliser cela en passant explicitement
 * {@code beanProvider(null)}.</p>
 */
public interface CassiniStack {

    /** L'adapter HTTP prêt à dispatcher des requêtes. */
    CassiniHttpAdapter adapter();

    static Builder builder() {
        Builder builder = ServiceLoader.load(BuilderFactory.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No CassiniStack implementation found. Is cassini-core on the classpath?"))
                .create();

        // Auto-discover BeanProvider via ServiceLoader, choose highest priority.
        ServiceLoader.load(BeanProvider.Factory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(BeanProvider.Factory::priority))
                .ifPresent(f -> builder.beanProvider(f.create()));

        return builder;
    }

    interface Builder {
        Builder application(Application app);
        Builder resourceFactory(ResourceFactory factory);

        /** Ajoute une instance {@code @Provider} enregistrée manuellement. */
        Builder provider(Object providerInstance);

        /**
         * Configure le {@link BeanProvider} utilisé pour résoudre les ressources
         * et providers managés. Si {@code null}, désactive tout BeanProvider
         * (y compris celui découvert automatiquement par {@link CassiniStack#builder()}).
         */
        Builder beanProvider(BeanProvider provider);

        CassiniStack build();
    }

    /** SPI — implémentée par cassini-core via ServiceLoader. */
    interface BuilderFactory {
        Builder create();
    }
}
