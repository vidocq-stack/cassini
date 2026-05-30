package io.vidocq.cassini.spi.http;

import java.time.Duration;

/**
 * Contract for suspending/resuming an in-flight request — backs
 * {@code @Suspended AsyncResponse} (JAX-RS §8) and the {@code CompletionStage}
 * values returned by resource methods.
 *
 * <p><b>M2h status</b>: this contract is frozen as of extraction. The current
 * implementation (Cassini 0.1.x) handles every async case blockingly via
 * {@code awaitBlocking()} in the Invoker. M2h will refactor the Invoker to
 * propagate stages all the way to this API without blocking.
 */
public interface CassiniAsyncContext {

    void suspend();

    void resume(Object entity);

    void resumeWithError(Throwable t);

    void setTimeout(Duration d, Runnable handler);

    void addCompletionCallback(Runnable cb);

    void addConnectionCallback(Runnable cb);

    boolean isSuspended();

    boolean isCancelled();

    boolean isDone();
}
