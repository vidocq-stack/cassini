package io.vidocq.cassini.spi.http;

import java.time.Duration;

/**
 * Contrat pour suspendre/reprendre une requête en cours — support de
 * {@code @Suspended AsyncResponse} (JAX-RS §8) et des {@code CompletionStage}
 * retournés par les méthodes resource.
 *
 * <p><b>Statut M2h</b> : ce contrat est figé dès l'extraction. L'implémentation
 * actuelle (Cassini 0.1.x) traite tous les async de façon bloquante via
 * {@code awaitBlocking()} dans l'Invoker. M2h refactorera l'Invoker pour
 * propager les stages jusqu'à cet API sans bloquer.
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
