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
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Source generation
    // -------------------------------------------------------------------------

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
        } else if (f.isBeanParam) {
            w.println(indent + "Object _v = support.beanParam(" + f.rawTypeLiteral + ");");
        } else {
            // param(kind, name, encoded, defaultValue, rawType, elementType)
            w.println(indent + "Object _v = support.param(");
            w.println(indent + "        ParamKind." + f.paramKind + ",");
            w.println(indent + "        \"" + f.paramAnnotationValue + "\",");
            w.println(indent + "        " + f.encoded + ",");
            if (f.defaultValue != null) {
                w.println(indent + "        \"" + escapeString(f.defaultValue) + "\",");
            } else {
                w.println(indent + "        null,");
            }
            w.println(indent + "        " + f.rawTypeLiteral + ",");
            w.println(indent + "        " + f.elementTypeLiteral + ");");
        }
        w.println(indent + "if (_v != null) " + f.varHandleField + ".set(target, _v);");
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
                    true, false, null, null, false, null, rawTypeLiteral, elementTypeLiteral);
        }
        if (hasAnnotation(ve, "jakarta.ws.rs.BeanParam")) {
            return new FieldModel(vhName, declaringBinary, javaName, fieldTypeLiteral,
                    false, true, null, null, false, null, rawTypeLiteral, elementTypeLiteral);
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

        return new FieldModel(vhName, declaringBinary, javaName, fieldTypeLiteral,
                false, false, paramKind, paramAnnotationValue, encoded, defaultValue,
                rawTypeLiteral, elementTypeLiteral);
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
            String elementTypeLiteral
    ) {}

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
