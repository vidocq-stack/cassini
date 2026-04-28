package io.vidocq.cassini.spi.http;

import java.util.concurrent.CompletionStage;

/**
 * Contrat pour pousser des chunks au fil de l'eau pendant que la méthode
 * resource est encore en cours d'exécution.
 *
 * <p>Utilisé par :
 * <ul>
 *   <li><b>SSE</b> (JAX-RS §11) — chaque {@code SseEventSink#send} écrit un
 *       chunk {@code event:/data:/...}.</li>
 *   <li><b>StreamingOutput async</b> — push progressif d'un body chunked-transfer.</li>
 * </ul>
 *
 * <p><b>Statut M2i</b> : l'implémentation actuelle de Cassini bufferise puis
 * émet en bloc à la fin de la méthode resource. M2i refactorera pour push réel
 * chunked-transfer via cet API.
 */
public interface CassiniStreamingSink {

    CompletionStage<Void> writeChunk(byte[] data);

    CompletionStage<Void> flush();

    CompletionStage<Void> close();

    boolean isOpen();
}
