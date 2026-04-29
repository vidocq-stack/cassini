package io.vidocq.cassini.examples.vauban.model;

/**
 * Modèle Todo — record immuable JSON-serializable.
 */
public record Todo(long id, String title, boolean done) {

    /**
     * Crée un Todo mis à jour avec un nouveau titre et statut.
     */
    public Todo withUpdate(String newTitle, boolean newDone) {
        return new Todo(this.id, newTitle, newDone);
    }
}
