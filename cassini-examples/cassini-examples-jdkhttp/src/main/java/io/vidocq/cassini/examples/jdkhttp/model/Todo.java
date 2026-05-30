package io.vidocq.cassini.examples.jdkhttp.model;

/**
 * Todo model — immutable JSON-serializable record.
 */
public record Todo(long id, String title, boolean done) {

    public Todo withUpdate(String newTitle, boolean newDone) {
        return new Todo(this.id, newTitle, newDone);
    }
}
