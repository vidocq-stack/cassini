package io.vidocq.cassini.examples.vauban.service;

import io.vidocq.cassini.examples.vauban.model.Todo;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service Todo — scope Application (singleton CDI).
 *
 * <p>Toute la logique métier est ici ; les ressources REST restent légères.</p>
 */
@ApplicationScoped
public class TodoService {

    private final Map<Long, Todo> store = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);

    /** Réinitialise le store (utilisé dans les tests). */
    public void reset() {
        store.clear();
        counter.set(0);
    }

    public List<Todo> list() {
        return new ArrayList<>(store.values());
    }

    public Todo create(String title) {
        long id = counter.incrementAndGet();
        var todo = new Todo(id, title, false);
        store.put(id, todo);
        return todo;
    }

    public Optional<Todo> get(long id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<Todo> update(long id, String title, boolean done) {
        var existing = store.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        var updated = existing.withUpdate(title, done);
        store.put(id, updated);
        return Optional.of(updated);
    }

    public boolean delete(long id) {
        return store.remove(id) != null;
    }
}
