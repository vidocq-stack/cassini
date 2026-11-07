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
package io.vidocq.cassini.client.internal;

import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

final class CassiniWebTarget implements WebTarget {

    private final CassiniClient client;
    private final UriBuilder uriBuilder;
    private final Map<String, Object> templateValues;
    /** This target's own configuration: a copy of its parent's, so what is set here stays here. */
    private final CassiniClientConfiguration configuration;

    CassiniWebTarget(CassiniClient client, UriBuilder uriBuilder, Map<String, Object> templateValues,
                     CassiniClientConfiguration configuration) {
        this.client = client;
        this.uriBuilder = uriBuilder;
        this.templateValues = templateValues;
        this.configuration = configuration;
    }

    @Override
    public URI getUri() {
        return templateValues.isEmpty() ? uriBuilder.build() : uriBuilder.buildFromMap(templateValues);
    }

    @Override public UriBuilder getUriBuilder() { return uriBuilder.clone(); }

    @Override
    public WebTarget path(String path) {
        return new CassiniWebTarget(client, uriBuilder.clone().path(path), new HashMap<>(templateValues), configuration.copy());
    }

    @Override
    public WebTarget resolveTemplate(String name, Object value) {
        Map<String, Object> m = new HashMap<>(templateValues);
        m.put(name, value);
        return new CassiniWebTarget(client, uriBuilder.clone(), m, configuration.copy());
    }

    @Override
    public WebTarget resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        return resolveTemplate(name, value);
    }

    @Override
    public WebTarget resolveTemplateFromEncoded(String name, Object value) {
        return resolveTemplate(name, value);
    }

    @Override
    public WebTarget resolveTemplates(Map<String, Object> templateValues) {
        Map<String, Object> m = new HashMap<>(this.templateValues);
        m.putAll(templateValues);
        return new CassiniWebTarget(client, uriBuilder.clone(), m, configuration.copy());
    }

    @Override
    public WebTarget resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) {
        return resolveTemplates(templateValues);
    }

    @Override
    public WebTarget resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        return resolveTemplates(templateValues);
    }

    @Override
    public WebTarget matrixParam(String name, Object... values) {
        return new CassiniWebTarget(client, uriBuilder.clone().matrixParam(name, values), new HashMap<>(templateValues), configuration.copy());
    }

    @Override
    public WebTarget queryParam(String name, Object... values) {
        return new CassiniWebTarget(client, uriBuilder.clone().queryParam(name, values), new HashMap<>(templateValues), configuration.copy());
    }

    @Override
    public Invocation.Builder request() {
        return new CassiniInvocationBuilder(client, configuration.copy(), getUri(), new String[]{MediaType.WILDCARD});
    }

    @Override
    public Invocation.Builder request(String... acceptedResponseTypes) {
        return new CassiniInvocationBuilder(client, configuration.copy(), getUri(), acceptedResponseTypes);
    }

    @Override
    public Invocation.Builder request(MediaType... acceptedResponseTypes) {
        String[] s = new String[acceptedResponseTypes.length];
        for (int i = 0; i < acceptedResponseTypes.length; i++) s[i] = acceptedResponseTypes[i].toString();
        return new CassiniInvocationBuilder(client, configuration.copy(), getUri(), s);
    }

    @Override public Configuration getConfiguration() { return configuration; }

    @Override
    public WebTarget property(String name, Object value) {
        configuration.putProperty(name, value);
        return this;
    }

    /** Registration goes through the feature context: one code path for contracts and features. */
    private CassiniClientFeatureContext registrar() { return new CassiniClientFeatureContext(configuration); }

    @Override public WebTarget register(Class<?> componentClass) { registrar().register(componentClass); return this; }
    @Override public WebTarget register(Class<?> componentClass, int priority) { registrar().register(componentClass, priority); return this; }
    @Override public WebTarget register(Class<?> componentClass, Class<?>... contracts) { registrar().register(componentClass, contracts); return this; }
    @Override public WebTarget register(Class<?> componentClass, Map<Class<?>, Integer> contracts) { registrar().register(componentClass, contracts); return this; }
    @Override public WebTarget register(Object component) { registrar().register(component); return this; }
    @Override public WebTarget register(Object component, int priority) { registrar().register(component, priority); return this; }
    @Override public WebTarget register(Object component, Class<?>... contracts) { registrar().register(component, contracts); return this; }
    @Override public WebTarget register(Object component, Map<Class<?>, Integer> contracts) { registrar().register(component, contracts); return this; }
}
