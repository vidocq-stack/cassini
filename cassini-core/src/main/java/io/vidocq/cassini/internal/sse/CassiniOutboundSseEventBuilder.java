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
package io.vidocq.cassini.internal.sse;

import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEvent;

import java.lang.reflect.Type;

final class CassiniOutboundSseEventBuilder implements OutboundSseEvent.Builder {
    private String id;
    private String name;
    private String comment;
    private long reconnectDelay = SseEvent.RECONNECT_NOT_SET;
    private MediaType mediaType = MediaType.TEXT_PLAIN_TYPE;
    private Class<?> type;
    private Type genericType;
    private Object data;

    @Override public OutboundSseEvent.Builder id(String id) { this.id = id; return this; }
    @Override public OutboundSseEvent.Builder name(String name) { this.name = name; return this; }
    @Override public OutboundSseEvent.Builder reconnectDelay(long ms) { this.reconnectDelay = ms; return this; }
    @Override public OutboundSseEvent.Builder mediaType(MediaType mt) {
        if (mt == null) throw new NullPointerException("mediaType");
        this.mediaType = mt;
        return this;
    }
    @Override public OutboundSseEvent.Builder comment(String comment) { this.comment = comment; return this; }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override public OutboundSseEvent.Builder data(Class type, Object data) {
        if (type == null) throw new NullPointerException("type");
        if (data == null) throw new NullPointerException("data");
        this.type = type;
        this.genericType = type;
        this.data = data;
        return this;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override public OutboundSseEvent.Builder data(GenericType type, Object data) {
        if (type == null) throw new NullPointerException("type");
        if (data == null) throw new NullPointerException("data");
        this.type = type.getRawType();
        this.genericType = type.getType();
        this.data = data;
        return this;
    }

    @Override public OutboundSseEvent.Builder data(Object data) {
        if (data == null) throw new NullPointerException("data");
        this.data = data;
        this.type = data.getClass();
        this.genericType = data.getClass();
        return this;
    }

    @Override public OutboundSseEvent build() {
        if (data == null && comment == null) {
            throw new IllegalStateException("At least one of data or comment must be set");
        }
        return new CassiniSse.CassiniOutboundSseEvent(id, name, comment, reconnectDelay,
                mediaType, type, genericType, data);
    }
}
