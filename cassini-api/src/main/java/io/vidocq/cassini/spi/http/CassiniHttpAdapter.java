package io.vidocq.cassini.spi.http;

import java.util.concurrent.CompletionStage;

/**
 * Point d'entrée serveur d'un transport HTTP qui pilote Cassini.
 *
 * <p>L'adapter (Chappe, JDK HttpServer, ...) délègue chaque requête entrante au
 * runtime Cassini en appelant {@link #dispatch(CassiniHttpExchange)}. Le retour
 * est un {@link CompletionStage} pour permettre la propagation correcte de la
 * fin de traitement (M2h : non-bloquant, virtual threads, async @Suspended).
 *
 * <p>En attendant M2h, l'implémentation actuelle peut retourner un stage déjà
 * complété — la signature reste async pour ne pas casser le contrat plus tard.
 */
public interface CassiniHttpAdapter {

    /**
     * Traite la requête entrante et écrit la réponse.
     *
     * @return un stage qui complète quand le body de réponse est entièrement
     *         écrit (ou que la connexion est fermée en cas d'erreur)
     */
    CompletionStage<Void> dispatch(CassiniHttpExchange exchange);
}
