package io.vidocq.cassini.internal;

import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cassini implementation of {@link AsyncResponse} (§8.2).
 *
 * <p>Each asynchronous request ({@code @Suspended AsyncResponse}) receives
 * an instance of this class. The resource method calls {@link #resume(Object)}
 * (from any thread) to send the response; the Invoker blocks the
 * current virtual thread on {@link #completionFuture()} until that call.
 *
 * <p>Blocking on a virtual thread is intentional (M2h): it releases the
 * carrier thread without starvation, in line with the JDK 25 virtual-threads model.
 */
public final class CassiniAsyncResponseImpl implements AsyncResponse {

    /** Exchange attribute key so that ParamExtractor can retrieve the instance. */
    public static final String ATTR_KEY = "cassini.async_response";

    private static final ScheduledExecutorService SCHEDULER;

    static {
        SCHEDULER = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "cassini-async-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    private final CompletableFuture<Object> future = new CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> timeoutTask;
    private volatile TimeoutHandler timeoutHandler;

    /** Stage completed when {@link #resume} or {@link #cancel} is called. */
    public CompletableFuture<Object> completionFuture() { return future; }

    @Override
    public boolean resume(Object response) {
        if (future.isDone()) return false;
        cancelTimeout();
        return future.complete(response);
    }

    @Override
    public boolean resume(Throwable response) {
        if (future.isDone()) return false;
        cancelTimeout();
        return future.completeExceptionally(response);
    }

    @Override
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        cancelTimeout();
        future.complete(Response.status(Response.Status.SERVICE_UNAVAILABLE).build());
        return true;
    }

    @Override
    public boolean cancel(int retryAfter) {
        if (!cancelled.compareAndSet(false, true)) return false;
        cancelTimeout();
        future.complete(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", retryAfter).build());
        return true;
    }

    @Override
    public boolean cancel(Date retryAfter) {
        if (!cancelled.compareAndSet(false, true)) return false;
        cancelTimeout();
        future.complete(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", retryAfter).build());
        return true;
    }

    @Override public boolean isSuspended() { return !future.isDone(); }
    @Override public boolean isCancelled()  { return cancelled.get(); }
    @Override public boolean isDone()        { return future.isDone(); }

    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        cancelTimeout();
        timeoutTask = SCHEDULER.schedule(() -> {
            if (!future.isDone()) {
                TimeoutHandler h = timeoutHandler;
                if (h != null) {
                    h.handleTimeout(this);
                } else {
                    future.complete(Response.status(Response.Status.SERVICE_UNAVAILABLE).build());
                }
            }
        }, time, unit);
        return true;
    }

    @Override
    public void setTimeoutHandler(TimeoutHandler handler) {
        this.timeoutHandler = handler;
    }

    @Override public Collection<Class<?>> register(Class<?> callback)                                        { return Collections.emptyList(); }
    @Override public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) { return Collections.emptyMap(); }
    @Override public Collection<Class<?>> register(Object callback)                                         { return Collections.emptyList(); }
    @Override public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks)     { return Collections.emptyMap(); }

    private void cancelTimeout() {
        ScheduledFuture<?> t = timeoutTask;
        if (t != null) {
            t.cancel(false);
            timeoutTask = null;
        }
    }
}
