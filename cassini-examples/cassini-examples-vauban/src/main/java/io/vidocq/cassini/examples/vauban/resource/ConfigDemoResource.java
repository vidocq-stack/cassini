package io.vidocq.cassini.examples.vauban.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Demonstration resource for Ravel + @ConfigProperty integration in Cassini.
 *
 * <p>Illustrates the injection of MicroProfile Config 3.1 (§6.1) configuration
 * properties into a JAX-RS resource via Vauban CDI + Ravel.</p>
 *
 * <p>Expected configuration in {@code META-INF/microprofile-config.properties}:</p>
 * <pre>
 * app.greeting=Hello
 * app.version=1.0
 * # app.env is optional
 * </pre>
 */
@ApplicationScoped
@Path("/config")
public class ConfigDemoResource {

    // Package-private (not private): the Vauban-APT-generated _VaubanComponents.injectField writes
    // these with an in-package putfield, so the container needs no `opens … to io.vidocq.vauban.core`.
    @Inject
    @ConfigProperty(name = "app.greeting", defaultValue = "Hello from Ravel")
    String greeting;

    @Inject
    @ConfigProperty(name = "app.version", defaultValue = "0.0.0")
    String version;

    @Inject
    @ConfigProperty(name = "app.env")
    Optional<String> environment;

    /**
     * Returns a greeting message configured via @ConfigProperty.
     *
     * @return greeting + version from the configuration
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getConfigInfo() {
        return greeting + " — v" + version
               + environment.map(e -> " [" + e + "]").orElse("");
    }

    /**
     * Returns configuration values as JSON.
     */
    @GET
    @Path("/details")
    @Produces(MediaType.APPLICATION_JSON)
    public String getConfigDetails() {
        return """
               {
                 "greeting": "%s",
                 "version": "%s",
                 "environment": %s
               }
               """.formatted(
                greeting,
                version,
                environment.map(e -> "\"" + e + "\"").orElse("null")
        );
    }
}

