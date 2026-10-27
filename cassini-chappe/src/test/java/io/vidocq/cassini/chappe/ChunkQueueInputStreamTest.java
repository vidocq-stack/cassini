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
package io.vidocq.cassini.chappe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The chunk queue behind a streamed (SSE) response: events go in from any thread, Chappe reads
 * them out, and the end of the stream ends the response. The queue holds 64 chunks.
 */
class ChunkQueueInputStreamTest {

    private static final int CAPACITY = 64;

    @Test
    void theEndOfAStreamWhoseQueueIsFullStillEndsTheResponse() {
        var stream = new ChappeHttpExchange.ChunkQueueInputStream();
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < CAPACITY; i++) stream.put(new byte[] {'x'});
                stream.endOfStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        int read = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // Read only once the queue is full and the producer has ended the stream: gone, or
            // waiting for room to put its end-of-stream mark.
            while (producer.isAlive()
                    && (stream.available() < CAPACITY || producer.getState() != Thread.State.TIMED_WAITING)) {
                Thread.onSpinWait();
            }
            int n = 0;
            while (stream.read() != -1) n++;
            return n;
        });
        assertEquals(CAPACITY, read);
    }

    @Test
    void closingTheStreamEndsItForTheReaderAndRefusesTheProducer() throws IOException {
        var stream = new ChappeHttpExchange.ChunkQueueInputStream();
        for (int i = 0; i < CAPACITY; i++) stream.put(new byte[] {'x'});
        stream.close();
        assertEquals(-1, stream.read());
        assertThrows(IOException.class, () -> stream.put(new byte[] {'y'}));
    }
}
