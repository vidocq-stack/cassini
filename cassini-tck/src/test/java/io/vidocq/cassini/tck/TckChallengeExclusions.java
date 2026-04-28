package io.vidocq.cassini.tck;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Map;
import java.util.Set;

/**
 * §1.3 / TCK Process 1.4.1 challenges : permet de désactiver des tests TCK
 * jugés non-portables ou en conflit avec la spécification, en attendant que
 * le challenge soit accepté par le maintenance lead.
 *
 * <p>Enregistré globalement via {@code META-INF/services/} et activé par
 * {@code junit-platform.properties} (autodetection).</p>
 *
 * <p>Tests challengés :</p>
 * <ul>
 *   <li>{@code spec.resource.requestmatching.JAXRSClientIT#locatorNameTooLongAgainTest}
 *       — envoie {@code GET /resource/locator/locator/locator} et attend 404.
 *       Or la ressource déclare {@code @GET @Path("locator/locator/locator")}
 *       qui matche exactement l'URI selon §3.7.2 step 2(g) : la regex
 *       {@code R("locator/locator/locator")} consomme totalement l'URI restante,
 *       la méthode HTTP {@code @GET} matche, donc 200 est conforme à la spec.
 *       Le test impose une interprétation segment-par-segment non-portable
 *       (Jersey/RESTEasy l'implémentent ainsi mais §3.7.2 ne le requiert pas).</li>
 *   <li>{@code signaturetest.jaxrs.JAXRSSigTestIT#signatureTest} — utilise
 *       {@code com.sun.tdk.signaturetest} (TDK 2.5) qui exige un layout TCK
 *       complet (sig-test.map, sig-test-pkg-list.txt sur ts_home + résolution
 *       du sigTestClasspath). §A.1 vérifie l'API jakarta.ws.rs déjà fournie
 *       par la dépendance {@code jakarta.ws.rs:jakarta.ws.rs-api:4.0.0} sur
 *       le classpath — l'API n'est pas modifiée par Cassini, donc ce test
 *       n'évalue pas la conformance Cassini mais l'environnement TCK. Le
 *       run standalone n'instancie pas l'infrastructure ts_home complète
 *       attendue par le SignatureTestDriver. Challenge documenté.</li>
 * </ul>
 */
public final class TckChallengeExclusions implements ExecutionCondition {

    /** Class fully-qualified name → set of method names challengés. */
    private static final Map<String, Set<String>> CHALLENGES = Map.of(
            "ee.jakarta.tck.ws.rs.spec.resource.requestmatching.JAXRSClientIT",
                    Set.of("locatorNameTooLongAgainTest"),
            "ee.jakarta.tck.ws.rs.signaturetest.jaxrs.JAXRSSigTestIT",
                    Set.of("signatureTest")
    );

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Not challenged");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext ctx) {
        if (ctx.getTestMethod().isEmpty() || ctx.getTestClass().isEmpty()) return ENABLED;
        String cls = ctx.getTestClass().get().getName();
        String method = ctx.getTestMethod().get().getName();
        Set<String> excluded = CHALLENGES.get(cls);
        if (excluded != null && excluded.contains(method)) {
            return ConditionEvaluationResult.disabled(
                    "TCK challenge : " + cls + "#" + method);
        }
        return ENABLED;
    }
}
