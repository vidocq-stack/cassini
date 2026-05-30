package io.vidocq.cassini.examples.chappe.model;

/**
 * Todo model — immutable JSON-serializable record.
 */
public record Todo(long id, String title, boolean done) {

    /**
     * Creates an updated Todo with a new title and status.
     */
    public Todo withUpdate(String newTitle, boolean newDone) {
        return new Todo(this.id, newTitle, newDone);
    }
}
