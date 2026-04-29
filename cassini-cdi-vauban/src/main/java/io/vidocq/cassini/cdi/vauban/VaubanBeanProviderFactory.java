package io.vidocq.cassini.cdi.vauban;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.vauban.core.container.VaubanContainer;

/**
 * Factory ServiceLoader pour {@link VaubanBeanProvider}.
 *
 * <p>Découverte par {@code CassiniStack.builder()} : si Vauban est sur le
 * classpath et qu'un container est démarré, l'instance courante est utilisée
 * comme {@link BeanProvider} par défaut.</p>
 */
public final class VaubanBeanProviderFactory implements BeanProvider.Factory {

    @Override
    public BeanProvider create() {
        var container = VaubanContainer.current();
        if (container == null) {
            throw new IllegalStateException(
                    "No Vauban CDI container is running — call VaubanContainer.builder().build() first");
        }
        return new VaubanBeanProvider(container);
    }

    @Override
    public int priority() { return 100; }
}
