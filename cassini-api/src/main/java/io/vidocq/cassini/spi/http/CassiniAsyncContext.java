package io.vidocq.cassini.spi.http;

import java.time.Duration;

/**
 * Contract for suspending/resuming an in-flight request — supports
 * {@code @Suspended AsyncResponse} (JAX-RS §8) and {@code CompletionStage}s
 * returned by resource methods.
 *
 * <p><b>M2h status</b>: this contract is frozen at extraction time. The current
 * implementation (Cassini 0.1.x) handles all async work in a blocking way via
 * {@code awaitBlocking()} in the Invoker. M2h will refactor the Invoker to
 * propagate stages to this API without blocking.
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
