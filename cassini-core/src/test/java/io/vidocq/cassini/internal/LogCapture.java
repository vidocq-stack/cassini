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
package io.vidocq.cassini.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures every record written to the named {@code java.util.logging} loggers —
 * which is where {@code System.Logger} lands when {@code java.logging} is in the
 * boot layer — at every level, for the lifetime of the capture. Holds the
 * {@link Logger}s strongly (JUL only keeps weak references) and restores their
 * level on {@link #close()}.
 */
final class LogCapture extends Handler implements AutoCloseable {

    private final List<Logger> loggers = new ArrayList<>();
    private final List<Level> previousLevels = new ArrayList<>();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    static LogCapture of(String... loggerNames) {
        LogCapture c = new LogCapture();
        c.setLevel(Level.ALL);
        for (String name : loggerNames) {
            Logger l = Logger.getLogger(name);
            c.loggers.add(l);
            c.previousLevels.add(l.getLevel());
            l.setLevel(Level.ALL);
            l.addHandler(c);
        }
        return c;
    }

    /** Records at or above {@code level} ({@code SEVERE} is where {@code System.Logger.Level.ERROR} lands). */
    List<LogRecord> atOrAbove(Level level) {
        return records.stream().filter(r -> r.getLevel().intValue() >= level.intValue()).toList();
    }

    /** Records at exactly {@code level} ({@code FINE} = DEBUG, {@code FINER} = TRACE). */
    List<LogRecord> at(Level level) {
        return records.stream().filter(r -> r.getLevel().equals(level)).toList();
    }

    @Override public void publish(LogRecord r) { records.add(r); }
    @Override public void flush() { }

    @Override
    public void close() {
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).removeHandler(this);
            loggers.get(i).setLevel(previousLevels.get(i));
        }
    }
}
