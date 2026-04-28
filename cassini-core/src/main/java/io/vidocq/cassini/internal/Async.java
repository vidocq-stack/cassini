package io.vidocq.cassini.internal;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Helpers pour la frontière sync ↔ async dans Cassini.
 *
 * <p><b>M2h — async non-bloquant + virtual threads</b></p>
 * <p>L'Invoker actuel exécute les méthodes resource en mode synchrone et
 * renvoie un {@link io.vidocq.cassini.internal.transport.CassiniHttpResponse}
 * directement. Quand une méthode resource retourne un {@link CompletionStage},
 * l'Invoker le résout en bloquant via {@link #awaitBlocking(CompletionStage)}.
 *
 * <p><b>Suppression M2h</b> : le refactor M2h propagera les {@code CompletionStage}
 * jusqu'au transport via {@link io.vidocq.cassini.spi.http.CassiniHttpAdapter#dispatch
 * CassiniHttpAdapter.dispatch} (qui retourne déjà {@code CompletionStage<Void>}).
 * Ce helper sera alors supprimé — toutes ses occurrences doivent être traitées
 * lors de M2h.
 *
 * <p>Pour faciliter le refactor M2h, isoler tous les blocages dans cette classe
 * (ne JAMAIS faire {@code .toCompletableFuture().get()} en ligne ailleurs).
 */
public final class Async {

    private Async() {}

    /**
     * Attend la complétion d'un {@link CompletionStage} de façon bloquante.
     *
     * <p><b>TODO(M2h)</b> : remplacer chaque appel par une propagation
     * non-bloquante du stage jusqu'au transport. Cf. {@code CassiniHttpAdapter.dispatch}.
     *
     * @param cs stage à attendre
     * @return la valeur résolue du stage
     * @throws RuntimeException si le stage échoue (cause préservée)
     */
    public static <T> T awaitBlocking(CompletionStage<T> cs) {
        try {
            return cs.toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while awaiting CompletionStage", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }
}
