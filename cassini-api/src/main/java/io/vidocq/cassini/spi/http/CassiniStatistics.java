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
package io.vidocq.cassini.spi.http;

/**
 * Live figures of one {@link CassiniStack}: how many requests it served, how they ended, how long they took.
 *
 * <p>A stack keeps them in a handful of counters it updates on every request, without a lock and without allocating.
 * Reading them does no I/O, takes no lock, blocks nothing and creates no resource or provider instance, so a host
 * may poll them from any thread, as often as it likes — once a second for a console, for instance. Each method reads
 * its counter at the time of the call: two calls, even back to back, may see different requests.
 *
 * <p>A request is timed from the moment the transport hands it to {@link CassiniHttpAdapter#dispatch} to the moment
 * Cassini has written its status, headers and body to the exchange: filters, routing, the resource method, the
 * exception mappers and serialisation included, the network excluded. A request suspended with
 * {@code @Suspended AsyncResponse} is timed until it is resumed. A server-sent event request is timed until its
 * resource method returns, which is not when the stream closes if the method hands the sink to another thread.
 */
public interface CassiniStatistics {

    /** The requests this stack answered since it was built, whatever their status. */
    long requests();

    /** The requests this stack is serving now: handed to it, not answered yet. */
    long inFlight();

    /**
     * The requests this stack answered with a status of the given class.
     *
     * @param statusClass {@code 1} for {@code 1xx} to {@code 5xx} for {@code 5xx}
     * @return how many, since the stack was built
     * @throws IllegalArgumentException when {@code statusClass} is not between 1 and 5
     */
    long responses(int statusClass);

    /**
     * The time spent serving the answered requests, added up, in nanoseconds: divide by {@link #requests()} for the
     * mean.
     */
    long totalNanos();

    /** The longest time one answered request took, in nanoseconds; {@code 0} before the first. */
    long maxNanos();
}
