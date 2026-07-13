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
package io.vidocq.cassini.internal.gen;

import io.vidocq.cassini.internal.ResourceMethod;
import io.vidocq.cassini.internal.ResourceScanner;
import io.vidocq.cassini.spi.gen.RouteDescriptor;
import io.vidocq.cassini.spi.gen.RouteProvider;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Build-time generator of {@code <ResourceClass>$$CassiniRoutes} bytecode — the {@link RouteProvider}
 * counterpart of {@link RuntimeAdapterGenerator}. While the APT ({@code cassini-processor}) emits
 * the routes provider from source, this generator emits it from a compiled {@link Class}, so the
 * {@code cassini-maven-plugin} can weave it into repackaged external/modular dependency JARs (closing
 * AOT coverage: {@code RouteRegistry} then uses the pre-generated provider instead of the reflective
 * {@code ResourceScanner} fallback).
 *
 * <p>Route extraction reuses {@link ResourceScanner} so the emitted {@link RouteDescriptor}s are
 * identical to what the runtime fallback would compute (behavioural parity by construction).</p>
 *
 * <p>Classes carrying sub-resource locators emit a provider whose {@link RouteProvider#hasLocators()}
 * returns {@code true} (and {@code routes()} is empty): per {@code RouteRegistry}, such classes must
 * keep using the full scanner at runtime, so there is nothing to pre-generate for them.</p>
 */
public final class RuntimeRoutesGenerator {

    public static final String ROUTES_SUFFIX = "$$CassiniRoutes";

    private static final ClassDesc CD_RouteProvider   = ClassDesc.of(RouteProvider.class.getName());
    private static final ClassDesc CD_RouteDescriptor = ClassDesc.of(RouteDescriptor.class.getName());
    private static final ClassDesc CD_List   = ClassDesc.of("java.util.List");
    private static final ClassDesc CD_String = ConstantDescs.CD_String;
    private static final ClassDesc CD_StringArray = CD_String.arrayType();
    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;
    private static final ClassDesc CD_Class  = ConstantDescs.CD_Class;

    // RouteDescriptor canonical ctor: (Class, String, String[], String, String, String[], String[], int)
    private static final MethodTypeDesc MTD_RD_INIT = MethodTypeDesc.of(
            ConstantDescs.CD_void, CD_Class, CD_String, CD_StringArray, CD_String, CD_String,
            CD_StringArray, CD_StringArray, ConstantDescs.CD_int);

    private RuntimeRoutesGenerator() {}

    public static String routesClassName(Class<?> resourceClass) {
        return resourceClass.getName() + ROUTES_SUFFIX;
    }

    /** Generates the {@code $$CassiniRoutes} provider bytecode for {@code resourceClass}. */
    public static byte[] toBytecode(Class<?> resourceClass) {
        boolean hasLocators = hasSubResourceLocators(resourceClass);
        List<ResourceMethod> routes = hasLocators ? List.of() : ResourceScanner.discover(resourceClass);

        ClassDesc providerCD = ClassDesc.of(routesClassName(resourceClass));
        ClassDesc resourceCD = ClassDesc.of(resourceClass.getName());

        return ClassFile.of().build(providerCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER | ClassFile.ACC_FINAL);
            clb.withInterfaceSymbols(CD_RouteProvider);

            // public no-arg constructor
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });

            // public boolean hasLocators()
            clb.withMethodBody("hasLocators", MethodTypeDesc.of(ConstantDescs.CD_boolean),
                    ClassFile.ACC_PUBLIC, cob -> {
                        if (hasLocators) cob.iconst_1(); else cob.iconst_0();
                        cob.ireturn();
                    });

            // public List<RouteDescriptor> routes()
            clb.withMethodBody("routes", MethodTypeDesc.of(CD_List),
                    ClassFile.ACC_PUBLIC, cob -> {
                        // Object[] tmp = new Object[N];
                        cob.loadConstant(routes.size());
                        cob.anewarray(CD_Object);
                        for (int i = 0; i < routes.size(); i++) {
                            ResourceMethod rm = routes.get(i);
                            cob.dup();
                            cob.loadConstant(i);
                            emitRouteDescriptor(cob, rm);
                            cob.aastore();
                        }
                        // return List.of(tmp);
                        cob.invokestatic(CD_List, "of",
                                MethodTypeDesc.of(CD_List, CD_Object.arrayType()), true);
                        cob.areturn();
                    });

            // public Class<?> resourceClass() { return Resource.class; }
            // ServiceLoader keying (zero-export Java Modules apps), parity with the APT.
            clb.withMethodBody("resourceClass", MethodTypeDesc.of(CD_Class),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.ldc(resourceCD);
                        cob.areturn();
                    });
        });
    }

    private static void emitRouteDescriptor(java.lang.classfile.CodeBuilder cob, ResourceMethod rm) {
        Method jm = rm.javaMethod();
        cob.new_(CD_RouteDescriptor);
        cob.dup();
        cob.loadConstant(ClassDesc.of(rm.beanClass().getName()));      // beanClass
        cob.loadConstant(jm.getName());                                // methodName
        emitStringArray(cob, paramTypeNames(jm));                      // paramTypeNames
        cob.loadConstant(rm.httpMethod());                             // httpMethod
        cob.loadConstant(rm.template().template());                    // pathTemplate
        emitStringArray(cob, rm.produces().toArray(new String[0]));    // produces
        emitStringArray(cob, rm.consumes().toArray(new String[0]));    // consumes
        cob.loadConstant(rm.classPathLiterals());                      // classPathLiterals
        cob.invokespecial(CD_RouteDescriptor, ConstantDescs.INIT_NAME, MTD_RD_INIT);
    }

    private static void emitStringArray(java.lang.classfile.CodeBuilder cob, String[] values) {
        cob.loadConstant(values.length);
        cob.anewarray(CD_String);
        for (int i = 0; i < values.length; i++) {
            cob.dup();
            cob.loadConstant(i);
            cob.loadConstant(values[i]);
            cob.aastore();
        }
    }

    /** Mirrors {@code RouteRegistry.resolveType}: primitives as-is, arrays as {@code X[]}, else binary name. */
    private static String[] paramTypeNames(Method m) {
        Class<?>[] params = m.getParameterTypes();
        String[] names = new String[params.length];
        for (int i = 0; i < params.length; i++) names[i] = typeName(params[i]);
        return names;
    }

    private static String typeName(Class<?> c) {
        return c.isArray() ? typeName(c.getComponentType()) + "[]" : c.getName();
    }

    /** A class has sub-resource locators if it declares a method with {@code @Path} but no HTTP verb. */
    private static boolean hasSubResourceLocators(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            if (m.isAnnotationPresent(Path.class) && !hasHttpVerb(m)) return true;
        }
        return false;
    }

    private static boolean hasHttpVerb(Method m) {
        for (var a : m.getAnnotations()) {
            if (a.annotationType().isAnnotationPresent(HttpMethod.class)) return true;
        }
        return false;
    }
}
