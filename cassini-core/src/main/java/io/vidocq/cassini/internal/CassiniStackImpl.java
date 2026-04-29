package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;

/**
 * Implémentation de {@link CassiniStack} — wraps un {@link DefaultCassiniHttpAdapter}.
 */
final class CassiniStackImpl implements CassiniStack {

    private final CassiniHttpAdapter adapter;

    CassiniStackImpl(CassiniHttpAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public CassiniHttpAdapter adapter() {
        return adapter;
    }
}
