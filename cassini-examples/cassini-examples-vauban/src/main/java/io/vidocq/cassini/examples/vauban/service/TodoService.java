/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * Todo service — Application scope (CDI singleton).
 *
 * <p>All business logic lives here; REST resources remain lightweight.</p>
 */
@ApplicationScoped
public class TodoService {

    private final Map<Long, Todo> store = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);

    /** Resets the store (used in tests). */
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
