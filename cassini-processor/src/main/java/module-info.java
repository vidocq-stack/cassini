/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
/**
 * APT annotation processor that generates {@code <ResourceClass>$$CassiniAdapter} sources
 * at compile time for every {@code @Path} and {@code @Provider} class.
 *
 * <p>The generated adapter is a plain Java source file emitted via {@code Filer} — no bytecode
 * manipulation, no ASM, no Byte Buddy. It implements
 * {@link io.vidocq.cassini.spi.gen.ResourceAdapter} and is picked up at runtime by
 * {@code AdapterRegistry.lookup} via {@code Class.forName} before the runtime generator is tried,
 * making the application AOT-safe (GraalVM native-image, Project Leyden CDS).</p>
 */
module io.vidocq.cassini.processor {
    requires java.compiler;
    requires io.vidocq.cassini.api;
    requires jakarta.ws.rs;

    exports io.vidocq.cassini.processor;

    provides javax.annotation.processing.Processor
            with io.vidocq.cassini.processor.CassiniResourceProcessor;
}
