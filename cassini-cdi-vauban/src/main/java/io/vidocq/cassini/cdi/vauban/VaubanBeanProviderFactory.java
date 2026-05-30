package io.vidocq.cassini.cdi.vauban;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.vauban.core.container.VaubanContainer;

/**
 * ServiceLoader factory for {@link VaubanBeanProvider}.
 *
 * <p>Discovered by {@code CassiniStack.builder()}: if Vauban is on the
 * classpath and a container is running, the current instance is used
 * as the default {@link BeanProvider}.</p>
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
