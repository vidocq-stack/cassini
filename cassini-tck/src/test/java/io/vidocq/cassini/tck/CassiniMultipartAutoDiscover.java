package io.vidocq.cassini.tck;

import io.vidocq.cassini.internal.multipart.MultipartFormDataProvider;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.FeatureContext;
import org.glassfish.jersey.internal.spi.AutoDiscoverable;

/**
 * §3.5.4 / Jersey AutoDiscoverable : enregistre le MBR/MBW multipart
 * {@link MultipartFormDataProvider} dès qu'un Jersey {@link jakarta.ws.rs.client.Client}
 * est créé via {@link jakarta.ws.rs.client.ClientBuilder}. Sans cela, le
 * client Jersey ne sait pas sérialiser/désérialiser {@code List<EntityPart>}
 * (il chercherait son type interne {@code BodyPart}).
 *
 * <p>Activé via {@code META-INF/services/org.glassfish.jersey.internal.spi.AutoDiscoverable}.</p>
 */
public final class CassiniMultipartAutoDiscover implements AutoDiscoverable {

    @Override
    public void configure(FeatureContext context) {
        if (context.getConfiguration().getRuntimeType() == RuntimeType.CLIENT) {
            if (!context.getConfiguration().isRegistered(MultipartFormDataProvider.class)) {
                context.register(MultipartFormDataProvider.class);
            }
            if (!context.getConfiguration().isRegistered(CassiniMultipartBoundaryFilter.class)) {
                context.register(CassiniMultipartBoundaryFilter.class);
            }
        }
    }
}
