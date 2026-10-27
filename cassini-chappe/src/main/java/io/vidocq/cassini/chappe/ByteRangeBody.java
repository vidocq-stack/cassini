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

import io.vidocq.chappe.api.Body;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * A response body over a range of an array, kept as it is: the entity buffer Cassini writes a
 * response into is longer than the body, and trimming it would copy the body once more. Chappe
 * reads a body through {@link #asInputStream()}; a {@link ByteArrayInputStream} keeps its
 * in-memory write path.
 */
final class ByteRangeBody implements Body {

    private final byte[] bytes;
    private final int offset;
    private final int length;

    ByteRangeBody(byte[] bytes, int offset, int length) {
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
    }

    @Override public long contentLength() {
        return length;
    }

    @Override public InputStream asInputStream() {
        return new ByteArrayInputStream(bytes, offset, length);
    }

    @Override public Flow.Publisher<ByteBuffer> asPublisher() {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override public void request(long n) {
                if (!done && n > 0) {
                    done = true;
                    subscriber.onNext(ByteBuffer.wrap(bytes, offset, length).asReadOnlyBuffer());
                    subscriber.onComplete();
                }
            }

            @Override public void cancel() {
                done = true;
            }
        });
    }
}
