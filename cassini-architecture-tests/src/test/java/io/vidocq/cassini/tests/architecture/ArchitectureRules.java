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
package io.vidocq.cassini.tests.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.LinkedHashMap;
import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Cassini's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Cassini's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.cassini.spi} (cassini-api),
 * {@code io.vidocq.cassini.internal} and {@code io.vidocq.cassini.runtime} (cassini-core),
 * {@code io.vidocq.cassini.processor} (cassini-processor), {@code io.vidocq.cassini.cdi} (cassini-cdi),
 * {@code io.vidocq.cassini.cdi.vauban} (cassini-cdi-vauban), {@code io.vidocq.cassini.chappe} (cassini-chappe),
 * {@code io.vidocq.cassini.jdkhttp} (cassini-jdk-http), {@code io.vidocq.cassini.client} (cassini-client) and
 * {@code io.vidocq.cassini.testing} (cassini-testing).
 */
final class ArchitectureRules {

    private static final String AGENTS = "workspace AGENTS.md, zero external dependency";
    private static final String CLAUDE_CONSTRAINTS = "CLAUDE.md, Architecture and Architectural constraints";

    /**
     * CLAUDE.md, Architecture: cassini-api is the public HTTP SPI and the codegen SPI. Its module descriptor reads
     * the REST API and, statically, the annotation API.
     */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cassini.spi.."},
            Packages.of(Jdk.RUNTIME, "jakarta.ws.rs..", "jakarta.annotation..", "io.vidocq.cassini.spi.."),
            CLAUDE_CONSTRAINTS + "; module-info.java of cassini-api");

    /**
     * CLAUDE.md, Architecture: cassini-processor is an annotation processor, {@code java.compiler} only at build
     * time, never shipped in the application; it generates sources from the language model and reads the SPI.
     */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cassini.processor.."},
            Packages.of(Jdk.COMPILER, "jakarta.ws.rs..", "io.vidocq.cassini.spi..", "io.vidocq.cassini.processor.."),
            CLAUDE_CONSTRAINTS + "; module-info.java of cassini-processor");

    /**
     * CLAUDE.md, Architectural constraints: no transport and no CDI container in cassini-core. Its module
     * descriptor reads the REST API, cassini-api, and the JSON-P/JSON-B, XML binding, activation and injection APIs
     * (the last three statically); {@code java.net.http} and {@code java.xml} are JDK modules.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cassini.internal..", "io.vidocq.cassini.runtime.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.ws.rs..",
                    "jakarta.annotation..",
                    "jakarta.inject..",
                    "jakarta.json..",
                    "jakarta.xml.bind..",
                    "jakarta.activation..",
                    "io.vidocq.cassini.spi..",
                    "io.vidocq.cassini.internal..",
                    "io.vidocq.cassini.runtime.."),
            AGENTS + "; " + CLAUDE_CONSTRAINTS + "; module-info.java of cassini-core");

    /**
     * CLAUDE.md, Architecture: cassini-cdi is the CDI bridge on the CDI API alone; the container is the
     * application's choice. Subject: the classes of {@code io.vidocq.cassini.cdi} outside the Vauban adapter.
     */
    static final ArchRule CDI_DEPENDENCIES = classes()
            .that()
            .resideInAPackage("io.vidocq.cassini.cdi..")
            .and()
            .resideOutsideOfPackage("io.vidocq.cassini.cdi.vauban..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.ws.rs..",
                    "jakarta.annotation..",
                    "io.vidocq.cassini.spi..",
                    "io.vidocq.cassini.cdi.."))
            .because("cassini-cdi depends on the CDI and REST APIs and cassini-api alone, never on a container"
                    + " (CLAUDE.md, Two resource instantiation modes; module-info.java of cassini-cdi)");

    /**
     * CLAUDE.md, Architecture: cassini-cdi-vauban is the optional Vauban adapter, the one module that uses
     * Vauban. Its module descriptor reads cassini-api, cassini-cdi, the CDI, injection and REST APIs and
     * vauban-core.
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cassini.cdi.vauban.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.ws.rs..",
                    "jakarta.annotation..",
                    "io.vidocq.vauban.core..",
                    "io.vidocq.cassini.spi..",
                    "io.vidocq.cassini.cdi.."),
            CLAUDE_CONSTRAINTS + "; module-info.java of cassini-cdi-vauban");

    /**
     * CLAUDE.md, Architecture: cassini-chappe is the Chappe transport adapter. Its module descriptor reads
     * cassini-api, cassini-core, chappe-api and, statically, the CDI API: Chappe's engine is the application's
     * choice, never Cassini's.
     */
    static final ArchRule CHAPPE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cassini.chappe.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.ws.rs..",
                    "jakarta.enterprise..",
                    "io.vidocq.chappe.api..",
                    "io.vidocq.cassini.spi..",
                    "io.vidocq.cassini.internal..",
                    "io.vidocq.cassini.chappe.."),
            CLAUDE_CONSTRAINTS + "; module-info.java of cassini-chappe");

    /**
     * CLAUDE.md, Architecture: cassini-jdk-http is the pure JDK adapter on {@code com.sun.net.httpserver}, with no
     * external dependency.
     */
    static final ArchRule JDK_HTTP_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cassini.jdkhttp.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "com.sun.net.httpserver..",
                    "jakarta.ws.rs..",
                    "io.vidocq.cassini.spi..",
                    "io.vidocq.cassini.internal..",
                    "io.vidocq.cassini.jdkhttp.."),
            CLAUDE_CONSTRAINTS + "; module-info.java of cassini-jdk-http");

    /**
     * CLAUDE.md, Architecture: cassini-client is the zero-dependency JAX-RS client on {@code java.net.http}. Its
     * module descriptor reads cassini-api, cassini-core, the REST, JSON-B and annotation APIs.
     */
    static final ArchRule CLIENT_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cassini.client.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.ws.rs..",
                    "jakarta.json..",
                    "jakarta.annotation..",
                    "io.vidocq.cassini.spi..",
                    "io.vidocq.cassini.internal..",
                    "io.vidocq.cassini.runtime..",
                    "io.vidocq.cassini.client.."),
            CLAUDE_CONSTRAINTS + "; module-info.java of cassini-client");

    /**
     * Published test support (cassini#36): the embedded harness on cassini-core and the Chappe adapter. Its module
     * descriptor reads cassini-api, cassini-core, cassini-chappe, chappe-api, the REST and CDI APIs.
     */
    static final ArchRule TESTING_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cassini.testing.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.ws.rs..",
                    "jakarta.enterprise..",
                    "io.vidocq.chappe.api..",
                    "io.vidocq.cassini.spi..",
                    "io.vidocq.cassini.internal..",
                    "io.vidocq.cassini.chappe..",
                    "io.vidocq.cassini.testing.."),
            CLAUDE_CONSTRAINTS + "; module-info.java of cassini-testing");

    /**
     * Workspace AGENTS.md, dependency graph: cassini-api is the stable SPI at the bottom, it never depends on
     * cassini-core.
     */
    static final ArchRule API_NEVER_DEPENDS_ON_CORE = VidocqArchRules.neverDependOn(
            new String[] {"io.vidocq.cassini.spi.."},
            new String[] {"io.vidocq.cassini.internal..", "io.vidocq.cassini.runtime.."},
            "workspace AGENTS.md, dependency graph; CLAUDE.md, Conventions");

    /**
     * Workspace AGENTS.md, dependency graph: Cassini composes the foundational bricks only through their APIs and
     * adapters. The API and the core never depend on another brick's implementation (Vauban, Champollion, Foy,
     * Mansart, Noether) and CLAUDE.md forbids any Chappe import in cassini-core.
     */
    static final ArchRule NO_OTHER_BRICK_IN_API_AND_CORE = VidocqArchRules.neverDependOn(
            new String[] {"io.vidocq.cassini.spi..", "io.vidocq.cassini.internal..", "io.vidocq.cassini.runtime.."},
            new String[] {
                "io.vidocq.vauban..",
                "io.vidocq.champollion..",
                "io.vidocq.chappe..",
                "io.vidocq.foy..",
                "io.vidocq.mansart..",
                "io.vidocq.noether.."
            },
            "workspace AGENTS.md, dependency graph; CLAUDE.md, Architectural constraints 1");

    /**
     * CLAUDE.md, Architecture and module descriptors: cassini-api is the bottom layer and reads no other Cassini
     * module; cassini-core builds on it and is reached by the transports, the client and the test support only;
     * cassini-cdi is reached by its Vauban adapter alone; the Chappe adapter by the test support alone; the
     * processor and the leaf adapters by nothing.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            CLAUDE_CONSTRAINTS + "; the module-info.java files",
            Layer.named("API").in("io.vidocq.cassini.spi..").accessesNoLayer(),
            Layer.named("Core")
                    .in("io.vidocq.cassini.internal..", "io.vidocq.cassini.runtime..")
                    .accessibleOnlyBy("Chappe adapter", "JDK HTTP adapter", "Client", "Test support"),
            Layer.named("Processor").in("io.vidocq.cassini.processor..").accessibleByNone(),
            Layer.named("CDI").in("io.vidocq.cassini.cdi").accessibleOnlyBy("CDI Vauban"),
            Layer.named("CDI Vauban").in("io.vidocq.cassini.cdi.vauban..").accessibleByNone(),
            Layer.named("Chappe adapter").in("io.vidocq.cassini.chappe..").accessibleOnlyBy("Test support"),
            Layer.named("JDK HTTP adapter").in("io.vidocq.cassini.jdkhttp..").accessibleByNone(),
            Layer.named("Client").in("io.vidocq.cassini.client..").accessibleByNone(),
            Layer.named("Test support").in("io.vidocq.cassini.testing..").accessibleByNone());

    /**
     * Reflective fallbacks (CLAUDE.md, "Documented residual reflection"; internals.adoc), each with its reason.
     * Nested classes share the entry of their top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = reflectiveFallbacks();

    /** Reason: the class only holds a {@code java.lang.reflect.Type}, which the JAX-RS API itself passes around. */
    private static final String API_TYPE = "carries the java.lang.reflect.Type of an entity, a type of the JAX-RS API"
            + " signatures (MessageBodyReader/Writer, interceptor and request contexts); it reads no member";

    /** Reason: type arguments of a generic entity or provider type, as the JAX-RS spec defines them. */
    private static final String GENERIC_TYPE = "resolves the type arguments of a generic entity or provider type"
            + " (ParameterizedType), as the JAX-RS spec defines them (GenericType, MessageBodyReader<T>,"
            + " ExceptionMapper<T>)";

    /** Reason: annotations of a class the application registers at run time. */
    private static final String ANNOTATIONS = "reads the JAX-RS or CDI annotations of an application class registered"
            + " at run time, a case with no build-time hook; the generated $$CassiniProvider descriptor replaces it"
            + " when present (internals.adoc, Generated code and reflective fallback)";

    /** Reason: one-time instantiation of an application class. */
    private static final String INSTANTIATION = "instantiates an application class once per scope, off the hot path"
            + " (CLAUDE.md and internals.adoc, Documented residual reflection: resource and bean instantiation)";

    /** Reason: the reflective safety net of the resource model. */
    private static final String RESOURCE_MODEL = "the reflective resource model: the startup scan of classes without"
            + " a generated $$CassiniRoutes, the FieldInjector net and the dispatch of methods that have no generated"
            + " adapter (CLAUDE.md and internals.adoc, Documented residual reflection)";

    /** Reason: the runtime codegen fallback reads the class it generates for. */
    private static final String CODEGEN_FALLBACK = "the codegen fallbacks (runtime adapter and route generation, adapter"
            + " lookup by name) read the resource class they generate for; the build-time path is the APT and the"
            + " Maven plugin (CLAUDE.md, M4 codegen architecture, levels 1 to 3)";

    /** Reason: an optional class looked up by name so that its API stays optional. */
    private static final String OPTIONAL_CLASS = "looks up an optional class by name without initialising it, so that"
            + " the dependency stays optional (zero-dependency rule)";

    private static Map<String, String> reflectiveFallbacks() {
        Map<String, String> fallbacks = new LinkedHashMap<>();
        String internal = "io.vidocq.cassini.internal.";
        // The entity type, as the JAX-RS API passes it.
        for (String type : new String[] {
            internal + "filter.CassiniReaderInterceptorContext",
            internal + "filter.CassiniWriterInterceptorContext",
            internal + "filter.CassiniResponseContext",
            internal + "sse.CassiniOutboundSseEventBuilder",
            internal + "sse.CassiniSse",
            "io.vidocq.cassini.client.internal.CassiniClientRequestContext"
        }) {
            fallbacks.put(type, API_TYPE);
        }
        // Generic entity and provider types.
        for (String generic : new String[] {
            internal + "ExceptionMapperRegistry",
            internal + "context.CassiniProviders",
            internal + "multipart.MultipartFormDataProvider",
            "io.vidocq.cassini.client.internal.CassiniAsyncInvoker",
            "io.vidocq.cassini.client.internal.ClientEntityJsonb"
        }) {
            fallbacks.put(generic, GENERIC_TYPE);
        }
        // Annotations of registered classes.
        for (String annotated : new String[] {
            internal + "context.CassiniUriInfo",
            internal + "runtime.CassiniUriBuilder",
            internal + "filter.FilterRegistry",
            internal + "filter.FilterEntry",
            internal + "ResponsePipeline",
            "io.vidocq.cassini.cdi.CdiBeanProvider",
            "io.vidocq.cassini.cdi.vauban.VaubanBeanProvider"
        }) {
            fallbacks.put(annotated, ANNOTATIONS);
        }
        // Instantiation of application classes.
        for (String instantiating : new String[] {
            "io.vidocq.cassini.spi.resource.ResourceFactory",
            internal + "CassiniStackBuilderImpl",
            internal + "CassiniAsyncResponseImpl",
            internal + "ParamExtractor",
            internal + "ParamValueConverter",
            internal + "filter.CassiniDynamicFeatureContext",
            internal + "gen.InjectionSupportImpl",
            internal + "runtime.CassiniRuntimeDelegate",
            "io.vidocq.cassini.chappe.ChappeRuntimeDelegate",
            "io.vidocq.cassini.jdkhttp.JdkHttpRuntimeDelegate",
            "io.vidocq.cassini.client.internal.CassiniClientConfiguration"
        }) {
            fallbacks.put(instantiating, INSTANTIATION);
        }
        // The reflective resource model.
        for (String model : new String[] {
            internal + "ResourceScanner",
            internal + "ResourceMethod",
            internal + "MethodParameters",
            internal + "InjectableFields",
            internal + "FieldInjector",
            internal + "Invoker",
            internal + "DynamicLocatorDispatch",
            internal + "CassiniStackImpl",
            internal + "DefaultCassiniHttpAdapter",
            internal + "RouteRegistry",
            internal + "EntityReadFailures"
        }) {
            fallbacks.put(model, RESOURCE_MODEL);
        }
        for (String generator : new String[] {
            internal + "gen.AdapterRegistry", internal + "gen.RuntimeAdapterGenerator", internal + "gen.RuntimeRoutesGenerator"
        }) {
            fallbacks.put(generator, CODEGEN_FALLBACK);
        }
        fallbacks.put(
                internal + "MessageBodyRegistry",
                "registers the built-in message body providers: tests whether JAXB and Activation are present by name,"
                        + " and reads the generic and annotation model of the providers it is given (" + OPTIONAL_CLASS + ")");
        fallbacks.put(
                internal + "json.CassiniJsonbReaderWriter",
                "tests whether jakarta.activation is present by name (" + OPTIONAL_CLASS + ")");
        fallbacks.put(
                "io.vidocq.cassini.client.internal.AsyncContextPropagator",
                "bridges the OpenTelemetry context by name, without any dependency on OpenTelemetry (MicroProfile"
                        + " Telemetry 2.1 needs the context carried to the async client); " + OPTIONAL_CLASS);
        fallbacks.put(
                "io.vidocq.cassini.testing.CassiniTestHarness",
                "test support: builds the test instance of a resource class by picking its injectable constructor");
        return Map.copyOf(fallbacks);
    }

    /**
     * Workspace AGENTS.md, "Maximum static code generation": what would be reflection at run time is generated at
     * build time (APT, Maven plugin, Class-File API). CLAUDE.md documents the residual reflection as accepted
     * exceptions, listed above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; CLAUDE.md, Documented residual reflection");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O": no platform-thread pool without a documented
     * reason. Every request runs on a virtual thread (CLAUDE.md, M2h; ASYNC.md). An executor given a thread factory,
     * as the async-timeout scheduler of cassini-core is (a virtual-thread factory), keeps the rule.
     */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.UNLESS_THREAD_FACTORY_GIVEN, "workspace AGENTS.md, Virtual Threads; ASYNC.md");

    /**
     * CLAUDE.md, M2h: cassini-core is ThreadLocal-free, per-request state lives in {@code RequestScope}, a
     * {@code ScopedValue}, and in exchange attributes; a request moves across virtual threads.
     */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "per-request state lives in a ScopedValue and in exchange attributes, a ThreadLocal does not follow a"
                    + " request across virtual threads",
            "CLAUDE.md, M2h; RequestScope");

    private ArchitectureRules() {
        // Rules only.
    }
}
