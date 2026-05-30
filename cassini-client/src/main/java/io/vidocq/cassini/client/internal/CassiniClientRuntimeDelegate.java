/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cassini.client.internal;

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;

/**
 * Local subclass of {@link CassiniRuntimeDelegate} — JPMS requires that
 * {@code provides ... with X} has {@code X} declared in the same module.
 *
 * <p>This indirection does not change runtime behaviour: all useful code is
 * inherited from {@code cassini-core}. It exists solely to satisfy the JPMS
 * contract and let cassini-client stay self-contained (usable without pulling
 * cassini-chappe or cassini-jdk-http at runtime).</p>
 */
public final class CassiniClientRuntimeDelegate extends CassiniRuntimeDelegate {

    public CassiniClientRuntimeDelegate() {
        super();
    }
}
