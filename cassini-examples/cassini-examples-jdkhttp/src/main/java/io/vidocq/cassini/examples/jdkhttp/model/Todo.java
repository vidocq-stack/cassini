package io.vidocq.cassini.examples.jdkhttp.model;

/**
 * Modèle Todo — record immuable JSON-serializable.
 */
public record Todo(long id, String title, boolean done) {

    public Todo withUpdate(String newTitle, boolean newDone) {
        return new Todo(this.id, newTitle, newDone);
    }
}
