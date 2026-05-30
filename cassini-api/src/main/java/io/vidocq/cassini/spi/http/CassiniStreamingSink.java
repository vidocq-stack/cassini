package io.vidocq.cassini.spi.http;

import java.util.concurrent.CompletionStage;

/**
 * Contract for pushing chunks as they become available while the resource
 * method is still executing.
 *
 * <p>Used by:
 * <ul>
 *   <li><b>SSE</b> (JAX-RS §11) — each {@code SseEventSink#send} writes a
 *       {@code event:/data:/...} chunk.</li>
 *   <li><b>StreamingOutput async</b> — progressive push of a chunked-transfer body.</li>
 * </ul>
 *
 * <p><b>M2i status</b>: the current Cassini implementation buffers and then
 * emits in one go at the end of the resource method. M2i will refactor to
 * real chunked-transfer push via this API.
 */
public interface CassiniStreamingSink {

    CompletionStage<Void> writeChunk(byte[] data);

    CompletionStage<Void> flush();

    CompletionStage<Void> close();

    boolean isOpen();
}
