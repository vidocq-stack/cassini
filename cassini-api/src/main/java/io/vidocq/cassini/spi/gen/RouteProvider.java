package io.vidocq.cassini.spi.gen;

import java.util.List;

/**
 * SPI implemented by generated {@code <ResourceClass>$$CassiniRoutes} classes.
 *
 * <p>One implementation is emitted per {@code @Path} resource class that has only
 * direct resource methods (no sub-resource locators / {@code dynamicLocator} routes).
 * When a class has locators, the generator emits <em>no</em> implementation (or sets
 * {@link #hasLocators()} to {@code true}), causing {@code RouteRegistry} to fall back
 * to {@code ResourceScanner.discover} for that class.</p>
 *
 * <p>Implementations are discovered by {@code RouteRegistry} via
 * {@code Class.forName("<ResourceClass>$$CassiniRoutes")} — the same lookup-by-name
 * pattern used by {@code AdapterRegistry} for {@code $$CassiniAdapter}.</p>
 */
public interface RouteProvider {

    /**
     * Returns the list of route descriptors for the resource class, encoded as
     * string/class literals — no reflection, no annotation access.
     *
     * @return immutable list of route descriptors
     */
    List<RouteDescriptor> routes();

    /**
     * Returns {@code true} if the resource class has sub-resource locators or
     * {@code dynamicLocator} catch-all routes that cannot be faithfully pre-generated.
     *
     * <p>When {@code true}, {@code RouteRegistry} discards this provider's {@link #routes()}
     * result (which will be empty) and falls back to {@code ResourceScanner.discover} for
     * the entire class.</p>
     *
     * <p>Default: {@code false} — generated implementations override only when needed.</p>
     */
    default boolean hasLocators() {
        return false;
    }
}
