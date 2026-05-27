/**
 * APT annotation processor that generates {@code <ResourceClass>$$CassiniAdapter} sources
 * at compile time for every {@code @Path} and {@code @Provider} class.
 *
 * <p>The generated adapter is a plain Java source file emitted via {@code Filer} — no bytecode
 * manipulation, no ASM, no Byte Buddy. It implements
 * {@link io.vidocq.cassini.spi.gen.ResourceAdapter} and is picked up at runtime by
 * {@code AdapterRegistry.lookup} via {@code Class.forName} before the runtime generator is tried,
 * making the application AOT-safe (GraalVM native-image, Project Leyden CDS).</p>
 */
module io.vidocq.cassini.processor {
    requires java.compiler;
    requires io.vidocq.cassini.api;
    requires jakarta.ws.rs;

    exports io.vidocq.cassini.processor;

    provides javax.annotation.processing.Processor
            with io.vidocq.cassini.processor.CassiniResourceProcessor;
}
