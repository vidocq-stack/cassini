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
 * Sous-classe locale de {@link CassiniRuntimeDelegate} — JPMS contraint le
 * {@code provides ... with X} à ce que {@code X} soit déclaré dans le même module.
 *
 * <p>Cette indirection ne change rien au comportement runtime : tout le code utile est
 * hérité de {@code cassini-core}. Elle existe uniquement pour satisfaire le contrat
 * JPMS et permettre à cassini-client de rester autonome (utilisable sans tirer
 * cassini-chappe ou cassini-jdk-http en runtime).</p>
 */
public final class CassiniClientRuntimeDelegate extends CassiniRuntimeDelegate {

    public CassiniClientRuntimeDelegate() {
        super();
    }
}
