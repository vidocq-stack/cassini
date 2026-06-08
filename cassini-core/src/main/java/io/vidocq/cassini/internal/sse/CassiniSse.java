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
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;

import java.lang.reflect.Type;

/**
 * Cassini implementation of {@link Sse} (server-side factory).
 *
 * <p>Minimal implementation to pass TCK §11:
 *  {@link #newEventBuilder()} produces a {@link CassiniOutboundSseEventBuilder};
 *  {@link #newBroadcaster()} returns a single-thread in-memory broadcaster.</p>
 */
public final class CassiniSse implements Sse {

    @Override
    public OutboundSseEvent.Builder newEventBuilder() {
        return new CassiniOutboundSseEventBuilder();
    }

    @Override
    public OutboundSseEvent newEvent(String data) {
        return newEventBuilder().data(data).build();
    }

    @Override
    public OutboundSseEvent newEvent(String name, String data) {
        return newEventBuilder().name(name).data(data).build();
    }

    @Override
    public SseBroadcaster newBroadcaster() {
        return new CassiniSseBroadcaster();
    }

    /** Représentation immuable d'un OutboundSseEvent. */
    static final class CassiniOutboundSseEvent implements OutboundSseEvent {
        private final String id;
        private final String name;
        private final String comment;
        private final long reconnectDelay;
        private final MediaType mediaType;
        private final Class<?> type;
        private final Type genericType;
        private final Object data;

        CassiniOutboundSseEvent(String id, String name, String comment, long reconnectDelay,
                                MediaType mediaType, Class<?> type, Type genericType, Object data) {
            this.id = id;
            this.name = name;
            this.comment = comment;
            this.reconnectDelay = reconnectDelay;
            this.mediaType = mediaType == null ? MediaType.TEXT_PLAIN_TYPE : mediaType;
            this.type = type;
            this.genericType = genericType;
            this.data = data;
        }

        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getComment() { return comment; }
        @Override public long getReconnectDelay() { return reconnectDelay; }
        @Override public boolean isReconnectDelaySet() { return reconnectDelay >= 0; }
        @Override public Class<?> getType() { return type; }
        @Override public Type getGenericType() { return genericType; }
        @Override public MediaType getMediaType() { return mediaType; }
        @Override public Object getData() { return data; }
    }
}
