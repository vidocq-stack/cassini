package io.vidocq.cassini.internal.sse;

import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;

import java.lang.reflect.Type;

/**
 * Implémentation Cassini de {@link Sse} (factory côté serveur).
 *
 * <p>Implémentation minimale pour passer le TCK §11 :
 *  {@link #newEventBuilder()} produit un {@link CassiniOutboundSseEventBuilder} ;
 *  {@link #newBroadcaster()} renvoie un broadcaster en mémoire single-thread.</p>
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
