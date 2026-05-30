package io.vidocq.cassini.tck;

import io.vidocq.cassini.internal.multipart.MultipartFormDataProvider;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.FeatureContext;
import org.glassfish.jersey.internal.spi.AutoDiscoverable;

/**
 * §3.5.4 / Jersey AutoDiscoverable: registers the multipart MBR/MBW
 * {@link MultipartFormDataProvider} as soon as a Jersey {@link jakarta.ws.rs.client.Client}
 * is created via {@link jakarta.ws.rs.client.ClientBuilder}. Without this, the
 * Jersey client cannot serialize/deserialize {@code List<EntityPart>}
 * (it would look up its internal {@code BodyPart} type).
 *
 * <p>Activated via {@code META-INF/services/org.glassfish.jersey.internal.spi.AutoDiscoverable}.</p>
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
