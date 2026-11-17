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
package io.vidocq.cassini.it.vauban;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * A provider of a bounded generic class that declares no scope: {@code CassiniScopeExtension} makes it
 * {@code @Dependent}. Its bean type is {@code NumberWriter<T extends Number>}, which the raw class does not resolve
 * under CDI 4.1 §5.2.4 (CASSINI-061).
 */
@Provider
@Produces(NumberWriter.MEDIA_TYPE)
public class NumberWriter<T extends Number> implements MessageBodyWriter<T> {

    public static final String MEDIA_TYPE = "application/x-number";

    @Inject
    Formats formats;

    @PostConstruct
    void created() {
        Lifecycles.created(NumberWriter.class);
    }

    @PreDestroy
    void destroyed() {
        Lifecycles.destroyed(NumberWriter.class);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Number.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(T value, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
            MultivaluedMap<String, Object> headers, OutputStream entity) throws IOException {
        entity.write(formats.number(value).getBytes(StandardCharsets.UTF_8));
    }
}
