package io.vidocq.cassini.spi.http;

import java.util.concurrent.CompletionStage;

/**
 * Contract for pushing chunks on the fly while the resource method is still
 * executing.
 *
 * <p>Used by:
 * <ul>
 *   <li><b>SSE</b> (JAX-RS §11) — each {@code SseEventSink#send} writes one
 *       {@code event:/data:/...} chunk.</li>
 *   <li><b>Async StreamingOutput</b> — progressive push of a chunked-transfer body.</li>
 * </ul>
 *
 * <p><b>M2i status</b>: the current Cassini implementation buffers and emits
 * everything at the end of the resource method. M2i will refactor it for real
 * chunked-transfer pushing via this API.
 */
public interface CassiniStreamingSink {

    CompletionStage<Void> writeChunk(byte[] data);

    CompletionStage<Void> flush();

    CompletionStage<Void> close();

    boolean isOpen();
}
