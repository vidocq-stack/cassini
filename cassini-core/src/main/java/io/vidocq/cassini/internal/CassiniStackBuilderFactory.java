package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.http.CassiniStack;

/**
 * ServiceLoader SPI — provides a {@link CassiniStack.Builder} from cassini-core.
 *
 * <p>Declared via {@code module-info.java:
 * provides io.vidocq.cassini.spi.http.CassiniStack.BuilderFactory
 *         with io.vidocq.cassini.internal.CassiniStackBuilderFactory;
 * }
 */
public final class CassiniStackBuilderFactory implements CassiniStack.BuilderFactory {

    @Override
    public CassiniStack.Builder create() {
        return new CassiniStackBuilderImpl();
    }
}
