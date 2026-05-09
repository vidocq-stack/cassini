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
 * Ressource de démonstration de l'intégration Ravel + @ConfigProperty dans Cassini.
 *
 * <p>Illustre l'injection de propriétés de configuration MicroProfile Config 3.1 (§6.1)
 * dans une ressource JAX-RS via Vauban CDI + Ravel.</p>
 *
 * <p>Configuration attendue dans {@code META-INF/microprofile-config.properties} :</p>
 * <pre>
 * app.greeting=Hello
 * app.version=1.0
 * # app.env est optionnel
 * </pre>
 */
@ApplicationScoped
@Path("/config")
public class ConfigDemoResource {

    @Inject
    @ConfigProperty(name = "app.greeting", defaultValue = "Hello from Ravel")
    private String greeting;

    @Inject
    @ConfigProperty(name = "app.version", defaultValue = "0.0.0")
    private String version;

    @Inject
    @ConfigProperty(name = "app.env")
    private Optional<String> environment;

    /**
     * Retourne un message de salutation configuré via @ConfigProperty.
     *
     * @return greeting + version depuis la configuration
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getConfigInfo() {
        return greeting + " — v" + version
               + environment.map(e -> " [" + e + "]").orElse("");
    }

    /**
     * Retourne les valeurs de configuration en JSON.
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

