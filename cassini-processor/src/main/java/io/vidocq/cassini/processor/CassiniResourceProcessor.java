package io.vidocq.cassini.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Annotation processor that generates {@code <ResourceClass>$$CassiniAdapter} source files
 * for every class annotated with {@code @jakarta.ws.rs.Path} or
 * {@code @jakarta.ws.rs.ext.Provider}.
 *
 * <p>The generated source implements {@link io.vidocq.cassini.spi.gen.ResourceAdapter} and
 * provides:</p>
 * <ul>
 *   <li>{@code injectFields}: VarHandle-based field injection (same semantics as
 *       {@code RuntimeAdapterGenerator}).</li>
 *   <li>{@code invoke}: direct typed dispatch switch over methods canonically ordered by
 *       {@code (declaringClassName, methodName, jvmDescriptor)} — identical to
 *       {@code RuntimeAdapterGenerator.collectMethods}.</li>
 * </ul>
 *
 * <p>{@code AdapterRegistry.lookup} tries {@code Class.forName(beanClass + "$$CassiniAdapter")}
 * first, so APT-generated adapters are used in preference to the runtime generator, making the
 * application AOT-safe (GraalVM native-image, Project Leyden CDS).</p>
 */
@SupportedAnnotationTypes({
        "jakarta.ws.rs.Path",
        "jakarta.ws.rs.ext.Provider"
})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class CassiniResourceProcessor extends AbstractProcessor {

    private static final String ADAPTER_SUFFIX = "$$CassiniAdapter";
    private static final String ROUTES_SUFFIX  = "$$CassiniRoutes";

    private Filer filer;
    private Messager messager;
    private Elements elements;
    private Types types;

    // Already-processed class names (binary) — avoid duplicate generation across rounds.
    private final Set<String> processed = new HashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.filer = env.getFiler();
        this.messager = env.getMessager();
        this.elements = env.getElementUtils();
        this.types = env.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        Set<TypeElement> toProcess = new LinkedHashSet<>();
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.CLASS) {
                    toProcess.add((TypeElement) element);
                }
            }
        }

        for (TypeElement resourceType : toProcess) {
            String binaryName = elements.getBinaryName(resourceType).toString();
            if (processed.contains(binaryName)) continue;
            processed.add(binaryName);
            try {
                generateAdapter(resourceType);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "cassini-processor: failed to generate adapter for "
                                + binaryName + ": " + e.getMessage(),
                        resourceType);
            }
            // Only generate $$CassiniRoutes for @Path classes (not @Provider-only classes)
            if (hasAnnotation(resourceType, "jakarta.ws.rs.Path")) {
                try {
                    generateRoutes(resourceType);
                } catch (Exception e) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                            "cassini-processor: failed to generate routes for "
                                    + binaryName + ": " + e.getMessage(),
                            resourceType);
                }
            }
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Source generation
    // -------------------------------------------------------------------------

    // ---- Route generation ---------------------------------------------------

    /**
     * Generates {@code <ResourceClass>$$CassiniRoutes} if the class has only direct resource
     * methods (no sub-resource locators). If it has locators, generates a stub that returns
     * {@code hasLocators() = true} so {@code RouteRegistry} falls back to
     * {@code ResourceScanner}.
     */
    private void generateRoutes(TypeElement resourceType) throws IOException {
        String binaryName  = elements.getBinaryName(resourceType).toString();
        String routesName  = binaryName + ROUTES_SUFFIX;

        int lastDot = routesName.lastIndexOf('.');
        String pkg          = lastDot > 0 ? routesName.substring(0, lastDot) : "";
        String simpleRoutes = lastDot > 0 ? routesName.substring(lastDot + 1) : routesName;

        // Collect resource methods and detect locators
        RouteCollection routes = collectRoutes(resourceType);

        JavaFileObject src = filer.createSourceFile(routesName, resourceType);
        try (PrintWriter w = new PrintWriter(src.openWriter())) {
            emitRoutes(w, resourceType, binaryName, pkg, simpleRoutes, routes);
        }
    }

    private void emitRoutes(PrintWriter w, TypeElement resourceType, String resourceBinaryName,
                            String pkg, String simpleRoutes, RouteCollection routes) {
        if (!pkg.isEmpty()) {
            w.println("package " + pkg + ";");
            w.println();
        }
        w.println("import io.vidocq.cassini.spi.gen.RouteDescriptor;");
        w.println("import io.vidocq.cassini.spi.gen.RouteProvider;");
        w.println("import java.util.List;");
        w.println();
        w.println("/**");
        w.println(" * APT-generated {@link RouteProvider} for {@code " + resourceBinaryName + "}.");
        w.println(" * Do not edit — regenerated by {@code cassini-processor} on each compilation.");
        w.println(" */");
        w.println("public final class " + simpleRoutes + " implements RouteProvider {");
        w.println();
        w.println("    public " + simpleRoutes + "() {}");
        w.println();

        if (routes.hasLocators) {
            // Has locators — signal fallback
            w.println("    @Override");
            w.println("    public boolean hasLocators() { return true; }");
            w.println();
            w.println("    @Override");
            w.println("    public List<RouteDescriptor> routes() { return List.of(); }");
        } else {
            w.println("    @Override");
            w.println("    public List<RouteDescriptor> routes() {");
            if (routes.methods.isEmpty()) {
                w.println("        return List.of();");
            } else {
                w.println("        return List.of(");
                for (int i = 0; i < routes.methods.size(); i++) {
                    RouteMethodModel rm = routes.methods.get(i);
                    boolean last = (i == routes.methods.size() - 1);
                    w.println("            new RouteDescriptor(");
                    w.println("                " + rm.beanClassLiteral + ",");
                    w.println("                \"" + escapeString(rm.methodName) + "\",");
                    // paramTypeNames
                    if (rm.paramTypeNames.isEmpty()) {
                        w.println("                new String[0],");
                    } else {
                        w.print("                new String[]{");
                        for (int j = 0; j < rm.paramTypeNames.size(); j++) {
                            if (j > 0) w.print(", ");
                            w.print("\"" + escapeString(rm.paramTypeNames.get(j)) + "\"");
                        }
                        w.println("},");
                    }
                    w.println("                \"" + escapeString(rm.httpMethod) + "\",");
                    w.println("                \"" + escapeString(rm.pathTemplate) + "\",");
                    // produces
                    emitStringArray(w, rm.produces, "                ");
                    w.println(",");
                    // consumes
                    emitStringArray(w, rm.consumes, "                ");
                    w.println(",");
                    w.print("                " + rm.classPathLiterals + ")");
                    if (!last) w.println(",");
                    else w.println();
                }
                w.println("        );");
            }
            w.println("    }");
        }

        w.println("}");
    }

    private void emitStringArray(PrintWriter w, List<String> values, String indent) {
        if (values.isEmpty()) {
            w.print(indent + "new String[0]");
        } else {
            w.print(indent + "new String[]{");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) w.print(", ");
                w.print("\"" + escapeString(values.get(i)) + "\"");
            }
            w.print("}");
        }
    }

    // -------------------------------------------------------------------------
    // Route model collection
    // -------------------------------------------------------------------------

    /** Collects resource methods and detects whether the class has locators. */
    private RouteCollection collectRoutes(TypeElement resourceType) {
        boolean hasLocators = false;
        List<RouteMethodModel> methods = new ArrayList<>();

        // Get class-level @Path, @Produces, @Consumes
        String classPath = annotationValue(resourceType, "jakarta.ws.rs.Path", "value");
        if (classPath == null) return new RouteCollection(false, List.of()); // not @Path
        String basePath = normalizePath(classPath);
        int classPathLiterals = countLiterals(basePath);
        List<String> classProduces = annotationValues(resourceType, "jakarta.ws.rs.Produces", "value");
        List<String> classConsumes = annotationValues(resourceType, "jakarta.ws.rs.Consumes", "value");
        String resourceBinaryName = elements.getBinaryName(resourceType).toString();
        String beanClassLiteral = resourceBinaryName.replace('$', '.') + ".class";

        // Collect all public non-static methods (declared on this class only — no inheritance
        // for route generation, consistent with how the TCK resources are structured).
        // We process declared methods first, then inherited, deduplicating by signature.
        Set<String> seen = new LinkedHashSet<>();
        List<ExecutableElement> allMethods = collectPublicInstanceMethods(resourceType);

        for (ExecutableElement m : allMethods) {
            String verb = resolveHttpVerb(m);
            String methodPathValue = annotationValue(m, "jakarta.ws.rs.Path", "value");

            if (verb == null) {
                // Sub-resource locator: @Path without HTTP verb
                if (methodPathValue != null) {
                    hasLocators = true;
                }
                // No verb, no @Path = skip
                continue;
            }

            // Direct resource method
            String fullPath = (methodPathValue == null)
                    ? basePath
                    : combinePaths(basePath, normalizePath(methodPathValue));

            List<String> methodProduces = annotationValues(m, "jakarta.ws.rs.Produces", "value");
            List<String> methodConsumes = annotationValues(m, "jakarta.ws.rs.Consumes", "value");
            List<String> effProduces = methodProduces.isEmpty() ? classProduces : methodProduces;
            List<String> effConsumes = methodConsumes.isEmpty() ? classConsumes : methodConsumes;

            // Parameter types for targeted reflection
            List<String> paramTypeNames = new ArrayList<>();
            for (VariableElement param : m.getParameters()) {
                paramTypeNames.add(jvmBinaryName(param.asType()));
            }

            methods.add(new RouteMethodModel(
                    beanClassLiteral,
                    m.getSimpleName().toString(),
                    paramTypeNames,
                    verb,
                    fullPath,
                    effProduces,
                    effConsumes,
                    classPathLiterals
            ));
        }

        // If any locators detected, signal fallback for the whole class
        if (hasLocators) {
            return new RouteCollection(true, List.of());
        }
        return new RouteCollection(false, List.copyOf(methods));
    }

    /** Collects all public non-static methods visible on the type (declared + inherited). */
    private List<ExecutableElement> collectPublicInstanceMethods(TypeElement type) {
        Set<String> seen = new LinkedHashSet<>();
        List<ExecutableElement> result = new ArrayList<>();
        collectMethodsRecursive(type, seen, result);
        return result;
    }

    private void collectMethodsRecursive(TypeElement type, Set<String> seen, List<ExecutableElement> result) {
        if (type == null || isObjectType(type)) return;
        for (Element enc : type.getEnclosedElements()) {
            if (enc.getKind() != ElementKind.METHOD) continue;
            ExecutableElement ee = (ExecutableElement) enc;
            if (!ee.getModifiers().contains(Modifier.PUBLIC)) continue;
            if (ee.getModifiers().contains(Modifier.STATIC)) continue;
            String sig = ee.getSimpleName().toString() + buildJvmParamDescriptor(ee);
            if (!seen.contains(sig)) {
                seen.add(sig);
                result.add(ee);
            }
        }
        // superclass
        TypeMirror superMirror = type.getSuperclass();
        if (superMirror != null && superMirror.getKind() != TypeKind.NONE) {
            Element superElem = ((DeclaredType) superMirror).asElement();
            if (superElem instanceof TypeElement) {
                collectMethodsRecursive((TypeElement) superElem, seen, result);
            }
        }
        // interfaces (for default methods)
        for (TypeMirror iface : type.getInterfaces()) {
            if (iface.getKind() == TypeKind.DECLARED) {
                Element ifaceElem = ((DeclaredType) iface).asElement();
                if (ifaceElem instanceof TypeElement) {
                    collectMethodsRecursive((TypeElement) ifaceElem, seen, result);
                }
            }
        }
    }

    /**
     * Resolves the HTTP verb for an executable element. Returns e.g. "GET", "POST", or null.
     * Handles both built-in annotations and custom @HttpMethod meta-annotations.
     */
    private String resolveHttpVerb(ExecutableElement m) {
        // Built-in verbs
        String[] builtinAnnotations = {
            "jakarta.ws.rs.GET", "jakarta.ws.rs.POST", "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE", "jakarta.ws.rs.HEAD", "jakarta.ws.rs.OPTIONS",
            "jakarta.ws.rs.PATCH"
        };
        String[] builtinVerbs = {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"};
        for (int i = 0; i < builtinAnnotations.length; i++) {
            if (hasAnnotation(m, builtinAnnotations[i])) return builtinVerbs[i];
        }
        // Custom @HttpMethod meta-annotations
        for (var mirror : m.getAnnotationMirrors()) {
            TypeElement annType = (TypeElement) mirror.getAnnotationType().asElement();
            // Check if the annotation itself is annotated with @HttpMethod
            String httpMethodValue = annotationValue(annType, "jakarta.ws.rs.HttpMethod", "value");
            if (httpMethodValue != null) return httpMethodValue;
        }
        return null;
    }

    /** Returns annotation values as a list (for array-valued attributes like @Produces/"value"). */
    @SuppressWarnings("unchecked")
    private List<String> annotationValues(Element e, String annotationFqn, String attributeName) {
        return e.getAnnotationMirrors().stream()
                .filter(am -> ((TypeElement) am.getAnnotationType().asElement())
                        .getQualifiedName().toString().equals(annotationFqn))
                .findFirst()
                .map(am -> am.getElementValues().entrySet().stream()
                        .filter(entry -> entry.getKey().getSimpleName().toString().equals(attributeName))
                        .findFirst()
                        .map(entry -> {
                            Object val = entry.getValue().getValue();
                            if (val instanceof List<?> list) {
                                List<String> result = new ArrayList<>();
                                for (Object item : list) {
                                    result.add(item.toString().replace("\"", ""));
                                }
                                return result;
                            }
                            return List.of(val.toString().replace("\"", ""));
                        })
                        .orElse(List.of()))
                .orElse(List.of());
    }

    /** Returns the binary name suitable for use in a generated Class.getDeclaredMethod call. */
    private String jvmBinaryName(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> "boolean";
            case BYTE    -> "byte";
            case SHORT   -> "short";
            case INT     -> "int";
            case LONG    -> "long";
            case FLOAT   -> "float";
            case DOUBLE  -> "double";
            case CHAR    -> "char";
            case VOID    -> "void";
            case ARRAY   -> jvmBinaryName(((ArrayType) t).getComponentType()) + "[]";
            case DECLARED -> {
                TypeElement te = (TypeElement) ((DeclaredType) t).asElement();
                yield elements.getBinaryName(te).toString();
            }
            case TYPEVAR -> "java.lang.Object";
            default -> "java.lang.Object";
        };
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String combinePaths(String base, String sub) {
        if ("/".equals(sub)) return base;
        if ("/".equals(base)) return sub;
        return base + sub;
    }

    private static int countLiterals(String path) {
        if (path == null) return 0;
        int n = 0; int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { if (depth > 0) depth--; }
            else if (depth == 0) n++;
        }
        return n;
    }

    // Internal models for route collection
    private record RouteCollection(boolean hasLocators, List<RouteMethodModel> methods) {}

    private record RouteMethodModel(
            String beanClassLiteral,
            String methodName,
            List<String> paramTypeNames,
            String httpMethod,
            String pathTemplate,
            List<String> produces,
            List<String> consumes,
            int classPathLiterals
    ) {}

    // ---- Adapter generation -------------------------------------------------

    private void generateAdapter(TypeElement resourceType) throws IOException {
        String binaryName   = elements.getBinaryName(resourceType).toString();
        String adapterName  = binaryName + ADAPTER_SUFFIX;

        // Derive package and simple name from binaryName.
        // binaryName uses '$' for nested classes — the adapter is always top-level.
        int lastDot = adapterName.lastIndexOf('.');
        String pkg          = lastDot > 0 ? adapterName.substring(0, lastDot) : "";
        String simpleAdapter = lastDot > 0 ? adapterName.substring(lastDot + 1) : adapterName;

        JavaFileObject src = filer.createSourceFile(adapterName, resourceType);
        try (PrintWriter w = new PrintWriter(src.openWriter())) {
            emitAdapter(w, resourceType, binaryName, pkg, simpleAdapter);
        }
    }

    private void emitAdapter(PrintWriter w, TypeElement resourceType,
                             String resourceBinaryName,
                             String pkg, String simpleAdapter) {

        List<FieldModel> fields = collectFields(resourceType);
        List<MethodModel> methods = collectMethods(resourceType);
        boolean hasNoArgCtor = hasAccessibleNoArgCtor(resourceType);

        if (!pkg.isEmpty()) {
            w.println("package " + pkg + ";");
            w.println();
        }

        w.println("import io.vidocq.cassini.spi.gen.InjectionSupport;");
        w.println("import io.vidocq.cassini.spi.gen.ParamKind;");
        w.println("import io.vidocq.cassini.spi.gen.ResourceAdapter;");
        w.println("import java.lang.invoke.MethodHandles;");
        w.println("import java.lang.invoke.VarHandle;");
        w.println();
        w.println("/**");
        w.println(" * APT-generated {@link ResourceAdapter} for {@code " + resourceBinaryName + "}.");
        w.println(" * Do not edit — regenerated by {@code cassini-processor} on each compilation.");
        w.println(" */");
        w.println("public final class " + simpleAdapter + " implements ResourceAdapter {");
        w.println();

        // Static VarHandle fields
        for (int i = 0; i < fields.size(); i++) {
            FieldModel f = fields.get(i);
            w.println("    private static final VarHandle " + f.varHandleField + ";");
        }

        // Static initializer
        if (!fields.isEmpty()) {
            w.println();
            w.println("    static {");
            w.println("        try {");
            // Use the resource class's own module-access: MethodHandles.lookup() inside the
            // generated class is in the resource's package (same package rule), so
            // privateLookupIn works for private fields without requiring opens.
            w.println("            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(");
            w.println("                    " + resourceBinaryName.replace('$', '.') + ".class,");
            w.println("                    MethodHandles.lookup());");
            for (FieldModel f : fields) {
                w.println("            " + f.varHandleField + " = lk.findVarHandle(");
                w.println("                    " + f.declaringBinaryName.replace('$', '.') + ".class,");
                w.println("                    \"" + f.javaFieldName + "\",");
                w.println("                    " + f.fieldTypeLiteral + ");");
            }
            w.println("        } catch (Throwable t) {");
            w.println("            throw new ExceptionInInitializerError(t);");
            w.println("        }");
            w.println("    }");
        }

        w.println();
        w.println("    public " + simpleAdapter + "() {}");
        w.println();

        // injectFields
        emitInjectFields(w, resourceBinaryName, fields);
        w.println();

        // invoke
        emitInvoke(w, resourceBinaryName, methods);
        w.println();

        // newInstance() — M6a
        emitNewInstance(w, resourceBinaryName, hasNoArgCtor);

        w.println("}");
    }

    // -------------------------------------------------------------------------
    // injectFields emission
    // -------------------------------------------------------------------------

    private void emitInjectFields(PrintWriter w, String resourceBinaryName,
                                   List<FieldModel> fields) {
        w.println("    @Override");
        w.println("    public void injectFields(Object target, InjectionSupport support, boolean injectParams) {");

        if (fields.isEmpty()) {
            w.println("        // no injectable fields");
            w.println("    }");
            return;
        }

        for (FieldModel f : fields) {
            w.println("        {");
            if (!f.isContext) {
                w.println("            if (!injectParams) { /* skip @*Param/@BeanParam when injectParams=false */ }");
                w.println("            else {");
                String indent = "                ";
                emitFieldResolution(w, f, indent);
                w.println("            }");
            } else {
                String indent = "            ";
                emitFieldResolution(w, f, indent);
            }
            w.println("        }");
        }

        w.println("    }");
    }

    private void emitFieldResolution(PrintWriter w, FieldModel f, String indent) {
        if (f.isContext) {
            w.println(indent + "Object _v = support.context(" + f.rawTypeLiteral + ");");
            w.println(indent + "if (_v != null) " + f.varHandleField + ".set(target, _v);");
        } else if (f.isBeanParam) {
            w.println(indent + "Object _v = support.beanParam(" + f.rawTypeLiteral + ");");
            w.println(indent + "if (_v != null) " + f.varHandleField + ".set(target, _v);");
        } else if (f.inlineStrategy == InlineStrategy.FALLBACK) {
            // Reflective fallback: support.param(kind, name, encoded, defaultValue, rawType, elementType)
            w.println(indent + "Object _v = support.param(");
            w.println(indent + "        ParamKind." + f.paramKind + ",");
            w.println(indent + "        \"" + escapeString(f.paramAnnotationValue) + "\",");
            w.println(indent + "        " + f.encoded + ",");
            if (f.defaultValue != null) {
                w.println(indent + "        \"" + escapeString(f.defaultValue) + "\",");
            } else {
                w.println(indent + "        null,");
            }
            w.println(indent + "        " + f.rawTypeLiteral + ",");
            w.println(indent + "        " + f.elementTypeLiteral + ");");
            w.println(indent + "if (_v != null) " + f.varHandleField + ".set(target, _v);");
        } else {
            // M6c: inline typed coercion (mirrors RuntimeAdapterGenerator.emitInlineParam).
            emitInlineParam(w, f, indent);
        }
    }

    /**
     * Emits inline typed {@code @*Param} field coercion. Mirrors the semantics of
     * {@code RuntimeAdapterGenerator.emitInlineParam}: obtain the raw {@code List<String>} from
     * {@code support.rawValues}, apply {@code @DefaultValue} when empty, convert (scalar or
     * collection), wrapping each conversion in {@code try/catch} so a {@code WebApplicationException}
     * propagates and any other {@code RuntimeException} becomes {@code support.coercionError(...)}.
     */
    private void emitInlineParam(PrintWriter w, FieldModel f, String indent) {
        String kindRef = "ParamKind." + f.paramKind;
        String nameLit = "\"" + escapeString(f.paramAnnotationValue) + "\"";
        boolean isCollection = f.inlineStrategy == InlineStrategy.COLLECTION_STRING
                || f.inlineStrategy == InlineStrategy.COLLECTION_INLINE;

        w.println(indent + "java.util.List<String> _raw = support.rawValues(" + kindRef + ", " + nameLit + ", " + f.encoded + ");");
        w.println(indent + "Object _v;");
        if (f.defaultValue != null) {
            w.println(indent + "if (_raw.isEmpty()) { _raw = java.util.List.of(\"" + escapeString(f.defaultValue) + "\"); }");
        }
        w.println(indent + "if (_raw.isEmpty()) {");
        w.println(indent + "    _v = " + emptyDefaultExpr(f) + ";");
        w.println(indent + "} else {");
        if (isCollection) {
            emitCollectionConvert(w, f, indent + "    ");
        } else {
            emitScalarConvertBlock(w, f, indent + "    ");
        }
        w.println(indent + "}");
        w.println(indent + "if (_v != null) " + f.varHandleField + ".set(target, _v);");
    }

    /** Boxed primitive zero for primitive fields, {@code null} for reference fields (empty, no default). */
    private String emptyDefaultExpr(FieldModel f) {
        if (!f.rawIsPrimitive) return "null";
        return switch (f.rawJavaTypeName) {
            case "boolean" -> "Boolean.valueOf(false)";
            case "byte"    -> "Byte.valueOf((byte) 0)";
            case "short"   -> "Short.valueOf((short) 0)";
            case "int"     -> "Integer.valueOf(0)";
            case "long"    -> "Long.valueOf(0L)";
            case "float"   -> "Float.valueOf(0f)";
            case "double"  -> "Double.valueOf(0d)";
            case "char"    -> "Character.valueOf('\\0')";
            default        -> "null";
        };
    }

    /** Scalar conversion: take {@code _raw.get(0)} and convert it, assigning to {@code _v}. */
    private void emitScalarConvertBlock(PrintWriter w, FieldModel f, String indent) {
        w.println(indent + "String _s = _raw.get(0);");
        if (f.elementStrategy == InlineStrategy.STRING) {
            w.println(indent + "_v = _s;");
            return;
        }
        w.println(indent + "try {");
        emitConvertStatement(w, f.elementStrategy, f.elementJavaTypeName, "_v", "_s", indent + "    ");
        w.println(indent + "} catch (jakarta.ws.rs.WebApplicationException _w) {");
        w.println(indent + "    throw _w;");
        w.println(indent + "} catch (RuntimeException _e) {");
        w.println(indent + "    throw support.coercionError(ParamKind." + f.paramKind + ", \""
                + escapeString(f.paramAnnotationValue) + "\", _e);");
        w.println(indent + "}");
    }

    /** Collection conversion: build the appropriate collection, converting each element inline. */
    private void emitCollectionConvert(PrintWriter w, FieldModel f, String indent) {
        String impl = switch (f.rawJavaTypeName) {
            case "java.util.Set"       -> "java.util.LinkedHashSet";
            case "java.util.SortedSet" -> "java.util.TreeSet";
            default                    -> "java.util.ArrayList"; // List, Collection
        };
        String elem = f.elementJavaTypeName;
        w.println(indent + impl + "<" + elem + "> _c = new " + impl + "<>();");
        w.println(indent + "for (int _i = 0; _i < _raw.size(); _i++) {");
        w.println(indent + "    String _s = _raw.get(_i);");
        if (f.inlineStrategy == InlineStrategy.COLLECTION_STRING) {
            w.println(indent + "    _c.add(_s);");
        } else {
            w.println(indent + "    " + elem + " _ev;");
            w.println(indent + "    try {");
            emitConvertStatement(w, f.elementStrategy, elem, "_ev", "_s", indent + "        ");
            w.println(indent + "    } catch (jakarta.ws.rs.WebApplicationException _w) {");
            w.println(indent + "        throw _w;");
            w.println(indent + "    } catch (RuntimeException _e) {");
            w.println(indent + "        throw support.coercionError(ParamKind." + f.paramKind + ", \""
                    + escapeString(f.paramAnnotationValue) + "\", _e);");
            w.println(indent + "    }");
            w.println(indent + "    _c.add(_ev);");
        }
        w.println(indent + "}");
        w.println(indent + "_v = _c;");
    }

    /**
     * Emits a single conversion statement assigning the converted value of {@code srcVar} (a String)
     * to {@code targetVar}. Mirrors {@code RuntimeAdapterGenerator.emitScalarConversion}.
     * {@code typeName} is the scalar/element Java type name (e.g. {@code com.foo.Color}).
     */
    private void emitConvertStatement(PrintWriter w, InlineStrategy strategy, String typeName,
                                      String targetVar, String srcVar, String indent) {
        switch (strategy) {
            case STRING  -> w.println(indent + targetVar + " = " + srcVar + ";");
            case BOOLEAN -> w.println(indent + targetVar + " = Boolean.parseBoolean(" + srcVar + ");");
            case BYTE    -> w.println(indent + targetVar + " = Byte.parseByte(" + srcVar + ");");
            case SHORT   -> w.println(indent + targetVar + " = Short.parseShort(" + srcVar + ");");
            case INT     -> w.println(indent + targetVar + " = Integer.parseInt(" + srcVar + ");");
            case LONG    -> w.println(indent + targetVar + " = Long.parseLong(" + srcVar + ");");
            case FLOAT   -> w.println(indent + targetVar + " = Float.parseFloat(" + srcVar + ");");
            case DOUBLE  -> w.println(indent + targetVar + " = Double.parseDouble(" + srcVar + ");");
            case CHAR -> {
                w.println(indent + "if (" + srcVar + ".isEmpty()) throw new IllegalArgumentException(\"Empty value for char\");");
                w.println(indent + targetVar + " = " + srcVar + ".charAt(0);");
            }
            case ENUM_WITH_FROM_STRING, FROM_STRING ->
                    w.println(indent + targetVar + " = " + typeName + ".fromString(" + srcVar + ");");
            case VALUE_OF ->
                    w.println(indent + targetVar + " = " + typeName + ".valueOf(" + srcVar + ");");
            case STRING_CTOR ->
                    w.println(indent + targetVar + " = new " + typeName + "(" + srcVar + ");");
            case ENUM_PLAIN -> {
                // Enum.valueOf(Type.class, raw); on IllegalArgumentException → null (matches coerceSingle)
                w.println(indent + "try {");
                w.println(indent + "    " + targetVar + " = Enum.valueOf(" + typeName + ".class, " + srcVar + ");");
                w.println(indent + "} catch (IllegalArgumentException _iae) {");
                w.println(indent + "    " + targetVar + " = null;");
                w.println(indent + "}");
            }
            default -> throw new IllegalStateException("Unexpected inline strategy: " + strategy);
        }
    }

    // -------------------------------------------------------------------------
    // invoke emission
    // -------------------------------------------------------------------------

    private void emitInvoke(PrintWriter w, String resourceBinaryName,
                             List<MethodModel> methods) {
        w.println("    @Override");
        w.println("    @SuppressWarnings(\"unchecked\")");
        w.println("    public Object invoke(int methodId, Object target, Object[] args) throws Throwable {");

        if (methods.isEmpty()) {
            w.println("        throw new UnsupportedOperationException(\"unknown methodId: \" + methodId);");
            w.println("    }");
            return;
        }

        w.println("        switch (methodId) {");
        for (int i = 0; i < methods.size(); i++) {
            MethodModel m = methods.get(i);
            w.println("            case " + i + ": {");
            // cast target
            String ownerLiteral = m.declaringBinaryName.replace('$', '.');
            w.println("                " + ownerLiteral + " _t = (" + ownerLiteral + ") target;");
            // load and cast each arg
            List<String> argExprs = new ArrayList<>();
            for (int j = 0; j < m.paramTypes.size(); j++) {
                ParamTypeModel pt = m.paramTypes.get(j);
                if (pt.isPrimitive) {
                    // unbox from Object
                    String wrapper = primitiveWrapper(pt.javaTypeName);
                    argExprs.add("((" + wrapper + ") args[" + j + "])." + pt.javaTypeName + "Value()");
                } else {
                    argExprs.add("(" + pt.javaTypeName + ") args[" + j + "]");
                }
            }
            String argsStr = String.join(", ", argExprs);
            if ("void".equals(m.returnTypeName)) {
                w.println("                _t." + m.methodName + "(" + argsStr + ");");
                w.println("                return null;");
            } else if (m.returnTypeIsPrimitive) {
                // box the return
                String wrapper = primitiveWrapper(m.returnTypeName);
                w.println("                return " + wrapper + ".valueOf(_t." + m.methodName + "(" + argsStr + "));");
            } else {
                w.println("                return _t." + m.methodName + "(" + argsStr + ");");
            }
            w.println("            }");
        }
        w.println("            default: throw new UnsupportedOperationException(\"unknown methodId: \" + methodId);");
        w.println("        }");
        w.println("    }");
    }

    // -------------------------------------------------------------------------
    // newInstance() emission (M6a)
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the resource type has a no-arg constructor that is accessible
     * from the adapter's package (public or package-private/protected; NOT private).
     * Used by the APT processor to decide whether to emit a generated {@code newInstance()}.
     */
    private boolean hasAccessibleNoArgCtor(TypeElement resourceType) {
        for (Element enc : resourceType.getEnclosedElements()) {
            if (enc.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement ctor = (ExecutableElement) enc;
            if (!ctor.getParameters().isEmpty()) continue;
            // no-arg constructor found — check accessibility
            Set<Modifier> mods = ctor.getModifiers();
            // private → not accessible from same-package adapter
            return !mods.contains(Modifier.PRIVATE);
        }
        // No explicit no-arg constructor: it is implicitly public when the class is public/package
        // and there are no other constructors. Check whether the class has any explicit constructors.
        long ctorCount = resourceType.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR)
                .count();
        if (ctorCount == 0) {
            // Implicit no-arg constructor exists (and has the same access as the class)
            Set<Modifier> classMods = resourceType.getModifiers();
            // Private nested class → not accessible; otherwise OK
            return !classMods.contains(Modifier.PRIVATE);
        }
        return false;
    }

    /**
     * Emits the {@code newInstance()} override. If {@code hasNoArgCtor} is {@code true},
     * emits {@code return new ResourceClass();}; otherwise the default interface method
     * (throws {@link UnsupportedOperationException}) is inherited — no override emitted.
     */
    private void emitNewInstance(PrintWriter w, String resourceBinaryName, boolean hasNoArgCtor) {
        if (!hasNoArgCtor) {
            // Let the interface default (throws UnsupportedOperationException) be inherited.
            return;
        }
        String resourceRef = resourceBinaryName.replace('$', '.');
        w.println("    @Override");
        w.println("    public Object newInstance() {");
        w.println("        return new " + resourceRef + "();");
        w.println("    }");
    }

    // -------------------------------------------------------------------------
    // Field model collection
    // -------------------------------------------------------------------------

    private List<FieldModel> collectFields(TypeElement resourceType) {
        List<FieldModel> result = new ArrayList<>();
        int index = 0;
        // Walk hierarchy: resourceType first, then superclasses, stopping at Object.
        TypeElement cls = resourceType;
        while (cls != null && !isObjectType(cls)) {
            // Sort declared fields by name for reproducibility (getDeclaredFields order is
            // JVM-internal; javax.lang.model has the same caveat).
            List<VariableElement> declared = new ArrayList<>();
            for (Element enc : cls.getEnclosedElements()) {
                if (enc.getKind() == ElementKind.FIELD) {
                    VariableElement ve = (VariableElement) enc;
                    if (!ve.getModifiers().contains(Modifier.STATIC)) {
                        declared.add(ve);
                    }
                }
            }
            declared.sort(Comparator.comparing(ve -> ve.getSimpleName().toString()));

            for (VariableElement ve : declared) {
                FieldModel fm = describeField(ve, cls, index);
                if (fm != null) {
                    result.add(fm);
                    index++;
                }
            }
            // move to superclass
            TypeMirror superMirror = cls.getSuperclass();
            if (superMirror == null || superMirror.getKind() == TypeKind.NONE) break;
            Element superElem = ((DeclaredType) superMirror).asElement();
            if (!(superElem instanceof TypeElement)) break;
            cls = (TypeElement) superElem;
        }
        return result;
    }

    private FieldModel describeField(VariableElement ve, TypeElement declaring, int index) {
        String javaName = ve.getSimpleName().toString();
        String vhName = "$$vh_" + index + "_" + javaName.replace('$', '_');
        String declaringBinary = elements.getBinaryName(declaring).toString();
        TypeMirror fType = ve.asType();
        String fieldTypeLiteral = classLiteral(fType);
        String rawTypeLiteral = fieldTypeLiteral;
        String elementTypeLiteral = fieldTypeLiteral;

        // Check annotations
        if (hasAnnotation(ve, "jakarta.ws.rs.core.Context")) {
            return new FieldModel(vhName, declaringBinary, javaName, fieldTypeLiteral,
                    true, false, null, null, false, null, rawTypeLiteral, elementTypeLiteral,
                    InlineStrategy.FALLBACK, InlineStrategy.FALLBACK, null, null, false);
        }
        if (hasAnnotation(ve, "jakarta.ws.rs.BeanParam")) {
            return new FieldModel(vhName, declaringBinary, javaName, fieldTypeLiteral,
                    false, true, null, null, false, null, rawTypeLiteral, elementTypeLiteral,
                    InlineStrategy.FALLBACK, InlineStrategy.FALLBACK, null, null, false);
        }

        boolean encoded = hasAnnotation(ve, "jakarta.ws.rs.Encoded")
                || hasAnnotation(declaring, "jakarta.ws.rs.Encoded");
        String defaultValue = annotationValue(ve, "jakarta.ws.rs.DefaultValue", "value");

        // Collection element type
        if (isListLike(fType)) {
            elementTypeLiteral = genericElementTypeLiteral(fType);
        }

        String paramKind = null;
        String paramAnnotationValue = null;

        String path = annotationValue(ve, "jakarta.ws.rs.PathParam", "value");
        if (path != null) { paramKind = "PATH"; paramAnnotationValue = path; }
        if (paramKind == null) {
            String query = annotationValue(ve, "jakarta.ws.rs.QueryParam", "value");
            if (query != null) { paramKind = "QUERY"; paramAnnotationValue = query; }
        }
        if (paramKind == null) {
            String header = annotationValue(ve, "jakarta.ws.rs.HeaderParam", "value");
            if (header != null) { paramKind = "HEADER"; paramAnnotationValue = header; }
        }
        if (paramKind == null) {
            String cookie = annotationValue(ve, "jakarta.ws.rs.CookieParam", "value");
            if (cookie != null) { paramKind = "COOKIE"; paramAnnotationValue = cookie; }
        }
        if (paramKind == null) {
            String matrix = annotationValue(ve, "jakarta.ws.rs.MatrixParam", "value");
            if (matrix != null) { paramKind = "MATRIX"; paramAnnotationValue = matrix; }
        }
        if (paramKind == null) {
            String form = annotationValue(ve, "jakarta.ws.rs.FormParam", "value");
            if (form != null) { paramKind = "FORM"; paramAnnotationValue = form; }
        }

        if (paramKind == null) return null; // not injectable

        // M6c: resolve the inline coercion strategy for this @*Param field.
        InlineStrategy inlineStrategy = resolveInlineStrategy(fType);
        TypeMirror scalarMirror = isListLike(fType) ? collectionElementMirror(fType) : fType;
        InlineStrategy elementStrategy = (scalarMirror != null)
                ? resolveScalarStrategy(scalarMirror) : InlineStrategy.FALLBACK;
        String rawJavaTypeName = javaTypeName(fType);
        String elementJavaTypeName = (scalarMirror != null) ? javaTypeName(scalarMirror) : "java.lang.String";
        boolean rawIsPrimitive = fType.getKind().isPrimitive();

        return new FieldModel(vhName, declaringBinary, javaName, fieldTypeLiteral,
                false, false, paramKind, paramAnnotationValue, encoded, defaultValue,
                rawTypeLiteral, elementTypeLiteral,
                inlineStrategy, elementStrategy, rawJavaTypeName, elementJavaTypeName, rawIsPrimitive);
    }

    // -------------------------------------------------------------------------
    // Method model collection — canonical order matches RuntimeAdapterGenerator
    // -------------------------------------------------------------------------

    private List<MethodModel> collectMethods(TypeElement resourceType) {
        List<MethodModel> eligible = new ArrayList<>();
        // getMethods() equivalent: collect all public non-static non-bridge non-synthetic
        // methods visible on the type (including inherited), excluding Object methods.
        collectPublicMethods(resourceType, new HashSet<>(), eligible);

        // Canonical sort: (declaringClassName, methodName, jvmParamDescriptor) — same as
        // RuntimeAdapterGenerator.collectMethods to ensure methodId alignment.
        eligible.sort(Comparator
                .comparing((MethodModel m) -> m.declaringBinaryName)
                .thenComparing(m -> m.methodName)
                .thenComparing(m -> m.jvmParamDescriptor));

        return eligible;
    }

    /**
     * Recursively collects all public instance methods from {@code type} and its superclass chain,
     * deduplicating by (name, jvmParamDescriptor) so overridden methods appear once (the override).
     */
    private void collectPublicMethods(TypeElement type, Set<String> seen, List<MethodModel> result) {
        if (type == null || isObjectType(type)) return;

        // Process declared methods first
        for (Element enc : type.getEnclosedElements()) {
            if (enc.getKind() != ElementKind.METHOD) continue;
            ExecutableElement ee = (ExecutableElement) enc;
            Set<Modifier> mods = ee.getModifiers();
            if (!mods.contains(Modifier.PUBLIC)) continue;
            if (mods.contains(Modifier.STATIC)) continue;
            // Bridge and synthetic cannot be directly detected in javax.lang.model;
            // we exclude methods that are purely synthetic overrides of generics (covariant
            // return bridges), but the model normally only exposes the non-bridge overrides.

            String paramDesc = buildJvmParamDescriptor(ee);
            String key = ee.getSimpleName().toString() + paramDesc;
            if (seen.contains(key)) continue;
            seen.add(key);

            MethodModel mm = describeMethod(ee, type);
            result.add(mm);
        }

        // Recurse into superclass
        TypeMirror superMirror = type.getSuperclass();
        if (superMirror != null && superMirror.getKind() != TypeKind.NONE) {
            Element superElem = ((DeclaredType) superMirror).asElement();
            if (superElem instanceof TypeElement) {
                collectPublicMethods((TypeElement) superElem, seen, result);
            }
        }
        // Also recurse into interfaces (for default methods)
        for (TypeMirror iface : type.getInterfaces()) {
            if (iface.getKind() == TypeKind.DECLARED) {
                Element ifaceElem = ((DeclaredType) iface).asElement();
                if (ifaceElem instanceof TypeElement) {
                    collectPublicMethods((TypeElement) ifaceElem, seen, result);
                }
            }
        }
    }

    private MethodModel describeMethod(ExecutableElement ee, TypeElement declaring) {
        String methodName = ee.getSimpleName().toString();
        String declaringBinary = elements.getBinaryName(declaring).toString();
        String paramDesc = buildJvmParamDescriptor(ee);

        List<ParamTypeModel> paramTypes = new ArrayList<>();
        for (VariableElement param : ee.getParameters()) {
            TypeMirror pt = param.asType();
            String javaTypeName = javaTypeName(pt);
            boolean isPrimitive = pt.getKind().isPrimitive();
            paramTypes.add(new ParamTypeModel(javaTypeName, isPrimitive));
        }

        TypeMirror returnMirror = ee.getReturnType();
        String returnTypeName = javaTypeName(returnMirror);
        boolean returnTypeIsPrimitive = returnMirror.getKind().isPrimitive();

        return new MethodModel(declaringBinary, methodName, paramDesc, paramTypes,
                returnTypeName, returnTypeIsPrimitive);
    }

    // -------------------------------------------------------------------------
    // Type utilities
    // -------------------------------------------------------------------------

    /**
     * Builds the JVM parameter descriptor string for canonical ordering.
     * Format: "(Ljava/lang/String;I)" — same logic as RuntimeAdapterGenerator.jvmDescriptor.
     */
    private String buildJvmParamDescriptor(ExecutableElement ee) {
        StringBuilder sb = new StringBuilder("(");
        for (VariableElement param : ee.getParameters()) {
            sb.append(toJvmDescriptor(param.asType()));
        }
        sb.append(")");
        return sb.toString();
    }

    private String toJvmDescriptor(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE    -> "B";
            case SHORT   -> "S";
            case INT     -> "I";
            case LONG    -> "J";
            case FLOAT   -> "F";
            case DOUBLE  -> "D";
            case CHAR    -> "C";
            case VOID    -> "V";
            case ARRAY   -> "[" + toJvmDescriptor(((ArrayType) t).getComponentType());
            case DECLARED -> {
                TypeElement te = (TypeElement) ((DeclaredType) t).asElement();
                String binary = elements.getBinaryName(te).toString().replace('.', '/');
                yield "L" + binary + ";";
            }
            case TYPEVAR -> "Ljava/lang/Object;"; // erased
            default -> "Ljava/lang/Object;";
        };
    }

    /** Returns the Java source name for a TypeMirror (for use in generated source). */
    private String javaTypeName(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> "boolean";
            case BYTE    -> "byte";
            case SHORT   -> "short";
            case INT     -> "int";
            case LONG    -> "long";
            case FLOAT   -> "float";
            case DOUBLE  -> "double";
            case CHAR    -> "char";
            case VOID    -> "void";
            case ARRAY   -> javaTypeName(((ArrayType) t).getComponentType()) + "[]";
            case DECLARED -> {
                TypeElement te = (TypeElement) ((DeclaredType) t).asElement();
                yield elements.getBinaryName(te).toString().replace('$', '.');
            }
            case TYPEVAR -> "Object"; // erased
            default -> "Object";
        };
    }

    /** Returns a {@code SomeClass.class} literal for use in generated source. */
    private String classLiteral(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> "boolean.class";
            case BYTE    -> "byte.class";
            case SHORT   -> "short.class";
            case INT     -> "int.class";
            case LONG    -> "long.class";
            case FLOAT   -> "float.class";
            case DOUBLE  -> "double.class";
            case CHAR    -> "char.class";
            case VOID    -> "void.class";
            case ARRAY   -> {
                // e.g. byte[].class
                String comp = javaTypeName(((ArrayType) t).getComponentType());
                yield comp + "[].class";
            }
            case DECLARED -> {
                TypeElement te = (TypeElement) ((DeclaredType) t).asElement();
                yield elements.getBinaryName(te).toString().replace('$', '.') + ".class";
            }
            case TYPEVAR -> "Object.class";
            default -> "Object.class";
        };
    }

    /**
     * Returns the element-type class literal for a collection-typed field.
     * E.g., for {@code List<String>} returns {@code "String.class"}.
     * Falls back to {@code "String.class"} when the type argument cannot be resolved.
     */
    private String genericElementTypeLiteral(TypeMirror t) {
        if (t.getKind() == TypeKind.DECLARED) {
            DeclaredType dt = (DeclaredType) t;
            if (!dt.getTypeArguments().isEmpty()) {
                TypeMirror arg = dt.getTypeArguments().get(0);
                if (arg.getKind() == TypeKind.DECLARED) {
                    TypeElement te = (TypeElement) ((DeclaredType) arg).asElement();
                    return elements.getBinaryName(te).toString().replace('$', '.') + ".class";
                }
            }
        }
        return "String.class";
    }

    private boolean isListLike(TypeMirror t) {
        if (t.getKind() != TypeKind.DECLARED) return false;
        TypeElement te = (TypeElement) ((DeclaredType) t).asElement();
        String name = te.getQualifiedName().toString();
        return "java.util.List".equals(name)
                || "java.util.Set".equals(name)
                || "java.util.SortedSet".equals(name)
                || "java.util.Collection".equals(name);
    }

    // -------------------------------------------------------------------------
    // M6c: inline coercion strategy resolution (javax.lang.model)
    // Mirrors RuntimeAdapterGenerator.resolveInlineStrategy / resolveScalarStrategy.
    // -------------------------------------------------------------------------

    /** First type argument of a collection-typed mirror, or {@code null} for a raw collection. */
    private TypeMirror collectionElementMirror(TypeMirror t) {
        if (t.getKind() == TypeKind.DECLARED) {
            DeclaredType dt = (DeclaredType) t;
            if (!dt.getTypeArguments().isEmpty()) {
                return dt.getTypeArguments().get(0);
            }
        }
        return null;
    }

    /** Top-level strategy: FALLBACK, a scalar strategy, or COLLECTION_STRING / COLLECTION_INLINE. */
    private InlineStrategy resolveInlineStrategy(TypeMirror fType) {
        if (isListLike(fType)) {
            TypeMirror elem = collectionElementMirror(fType);
            if (elem == null) return InlineStrategy.FALLBACK; // raw collection
            InlineStrategy es = resolveScalarStrategy(elem);
            if (es == InlineStrategy.FALLBACK) return InlineStrategy.FALLBACK;
            if (es == InlineStrategy.STRING) return InlineStrategy.COLLECTION_STRING;
            return InlineStrategy.COLLECTION_INLINE;
        }
        return resolveScalarStrategy(fType);
    }

    /** Scalar (non-collection) strategy for a single value type. */
    private InlineStrategy resolveScalarStrategy(TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN: return InlineStrategy.BOOLEAN;
            case BYTE:    return InlineStrategy.BYTE;
            case SHORT:   return InlineStrategy.SHORT;
            case INT:     return InlineStrategy.INT;
            case LONG:    return InlineStrategy.LONG;
            case FLOAT:   return InlineStrategy.FLOAT;
            case DOUBLE:  return InlineStrategy.DOUBLE;
            case CHAR:    return InlineStrategy.CHAR;
            default: break;
        }
        if (type.getKind() != TypeKind.DECLARED) return InlineStrategy.FALLBACK;
        TypeElement te = (TypeElement) ((DeclaredType) type).asElement();
        String qn = te.getQualifiedName().toString();

        // String / CharSequence pass-through
        if ("java.lang.String".equals(qn) || "java.lang.CharSequence".equals(qn)) {
            return InlineStrategy.STRING;
        }
        // Wrappers (parse like primitives)
        switch (qn) {
            case "java.lang.Boolean":   return InlineStrategy.BOOLEAN;
            case "java.lang.Byte":      return InlineStrategy.BYTE;
            case "java.lang.Short":     return InlineStrategy.SHORT;
            case "java.lang.Integer":   return InlineStrategy.INT;
            case "java.lang.Long":      return InlineStrategy.LONG;
            case "java.lang.Float":     return InlineStrategy.FLOAT;
            case "java.lang.Double":    return InlineStrategy.DOUBLE;
            case "java.lang.Character": return InlineStrategy.CHAR;
            default: break;
        }
        // PathSegment: fall back (complex parse — not worth inlining)
        if ("jakarta.ws.rs.core.PathSegment".equals(qn) || isPathSegment(type)) {
            return InlineStrategy.FALLBACK;
        }
        // Must be publicly referenceable from the generated adapter (and all enclosing types public).
        if (!isPublicType(te)) return InlineStrategy.FALLBACK;

        // Enum: prefer public static fromString(String), else Enum.valueOf
        if (te.getKind() == ElementKind.ENUM) {
            return hasPublicStaticStringMethod(te, "fromString")
                    ? InlineStrategy.ENUM_WITH_FROM_STRING
                    : InlineStrategy.ENUM_PLAIN;
        }
        if (hasPublicStaticStringMethod(te, "valueOf"))   return InlineStrategy.VALUE_OF;
        if (hasPublicStaticStringMethod(te, "fromString")) return InlineStrategy.FROM_STRING;
        if (hasPublicStringConstructor(te))                return InlineStrategy.STRING_CTOR;

        return InlineStrategy.FALLBACK;
    }

    /** True if {@code type} is (a subtype of) {@code jakarta.ws.rs.core.PathSegment}. */
    private boolean isPathSegment(TypeMirror type) {
        TypeElement ps = elements.getTypeElement("jakarta.ws.rs.core.PathSegment");
        if (ps == null) return false;
        try {
            return types.isAssignable(types.erasure(type), types.erasure(ps.asType()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when the type and all its enclosing types are {@code public}. */
    private boolean isPublicType(TypeElement te) {
        Element e = te;
        while (e instanceof TypeElement t) {
            if (!t.getModifiers().contains(Modifier.PUBLIC)) return false;
            Element enc = t.getEnclosingElement();
            if (enc instanceof TypeElement) { e = enc; } else { break; }
        }
        return true;
    }

    /** True if {@code te} declares a {@code public static <Type> name(String)} method. */
    private boolean hasPublicStaticStringMethod(TypeElement te, String name) {
        for (Element enc : te.getEnclosedElements()) {
            if (enc.getKind() != ElementKind.METHOD) continue;
            ExecutableElement m = (ExecutableElement) enc;
            if (!m.getSimpleName().contentEquals(name)) continue;
            Set<Modifier> mods = m.getModifiers();
            if (!mods.contains(Modifier.STATIC) || !mods.contains(Modifier.PUBLIC)) continue;
            if (m.getParameters().size() != 1) continue;
            if (isStringType(m.getParameters().get(0).asType())) return true;
        }
        return false;
    }

    /** True if {@code te} declares a {@code public <Type>(String)} constructor. */
    private boolean hasPublicStringConstructor(TypeElement te) {
        for (Element enc : te.getEnclosedElements()) {
            if (enc.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement c = (ExecutableElement) enc;
            if (!c.getModifiers().contains(Modifier.PUBLIC)) continue;
            if (c.getParameters().size() != 1) continue;
            if (isStringType(c.getParameters().get(0).asType())) return true;
        }
        return false;
    }

    private boolean isStringType(TypeMirror t) {
        if (t.getKind() != TypeKind.DECLARED) return false;
        return "java.lang.String".equals(
                ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().toString());
    }

    private boolean isObjectType(TypeElement te) {
        return "java.lang.Object".equals(te.getQualifiedName().toString());
    }

    // -------------------------------------------------------------------------
    // Annotation utilities
    // -------------------------------------------------------------------------

    private boolean hasAnnotation(Element e, String fqn) {
        return e.getAnnotationMirrors().stream()
                .anyMatch(am -> ((TypeElement) am.getAnnotationType().asElement())
                        .getQualifiedName().toString().equals(fqn));
    }

    @SuppressWarnings("unchecked")
    private String annotationValue(Element e, String annotationFqn, String attributeName) {
        return e.getAnnotationMirrors().stream()
                .filter(am -> ((TypeElement) am.getAnnotationType().asElement())
                        .getQualifiedName().toString().equals(annotationFqn))
                .findFirst()
                .flatMap(am -> am.getElementValues().entrySet().stream()
                        .filter(entry -> entry.getKey().getSimpleName().toString().equals(attributeName))
                        .findFirst()
                        .map(entry -> entry.getValue().getValue().toString()))
                .orElse(null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String primitiveWrapper(String primitive) {
        return switch (primitive) {
            case "int"     -> "Integer";
            case "long"    -> "Long";
            case "double"  -> "Double";
            case "float"   -> "Float";
            case "boolean" -> "Boolean";
            case "short"   -> "Short";
            case "byte"    -> "Byte";
            case "char"    -> "Character";
            default        -> "Object";
        };
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -------------------------------------------------------------------------
    // Internal models
    // -------------------------------------------------------------------------

    private record FieldModel(
            String varHandleField,
            String declaringBinaryName,
            String javaFieldName,
            String fieldTypeLiteral,
            boolean isContext,
            boolean isBeanParam,
            String paramKind,
            String paramAnnotationValue,
            boolean encoded,
            String defaultValue,
            String rawTypeLiteral,
            String elementTypeLiteral,
            // ---- M6c: inline coercion metadata (PARAM fields only) ----
            InlineStrategy inlineStrategy,   // top-level: FALLBACK / scalar / COLLECTION_*
            InlineStrategy elementStrategy,  // scalar strategy of the (element) type to convert
            String rawJavaTypeName,          // e.g. "int", "java.util.List", "com.foo.Color"
            String elementJavaTypeName,      // scalar/element java type name to reference in source
            boolean rawIsPrimitive           // true when the field's declared type is a primitive
    ) {}

    /**
     * Inline conversion strategy for a {@code @*Param} field — mirrors
     * {@code RuntimeAdapterGenerator.InlineStrategy} (M6b). {@code FALLBACK} means: emit the
     * reflective {@code support.param(...)} call; every other value triggers inline typed
     * conversion via {@code support.rawValues(...)} + {@code support.coercionError(...)}.
     */
    enum InlineStrategy {
        FALLBACK,
        STRING,           // String / CharSequence pass-through
        BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, CHAR,  // primitives + wrappers
        ENUM_WITH_FROM_STRING,  // enum with public static fromString(String)
        ENUM_PLAIN,             // enum without fromString — use Enum.valueOf
        VALUE_OF,               // public static valueOf(String)
        FROM_STRING,            // public static fromString(String)
        STRING_CTOR,            // public (String) constructor
        COLLECTION_STRING,      // List/Set/SortedSet/Collection of String
        COLLECTION_INLINE       // List/Set/SortedSet/Collection of an inlinable element type
    }

    private record MethodModel(
            String declaringBinaryName,
            String methodName,
            String jvmParamDescriptor,
            List<ParamTypeModel> paramTypes,
            String returnTypeName,
            boolean returnTypeIsPrimitive
    ) {}

    private record ParamTypeModel(
            String javaTypeName,
            boolean isPrimitive
    ) {}
}
