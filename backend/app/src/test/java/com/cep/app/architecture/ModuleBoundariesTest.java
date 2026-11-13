package com.cep.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Enforces the modular-monolith contract: a module's {@code internal} package is private to that module.
 * Other modules may only use its {@code api} package. This keeps each module extractable as a service.
 */
class ModuleBoundariesTest {
    static JavaClasses classes;

    static List<String> modules() {
        return List.of("shared", "auth", "app");
    }

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.cep");
    }

    @ParameterizedTest(name = "{0}.internal is private to {0}")
    @MethodSource("modules")
    void internalPackagesArePrivate(String module) {
        noClasses().that().resideOutsideOfPackage("com.cep." + module + "..")
                .should().dependOnClassesThat().resideInAPackage("com.cep." + module + ".internal..")
                .because("modules communicate only through their api packages")
                .check(classes);
    }

    @ParameterizedTest(name = "{0}.api does not depend on internals")
    @MethodSource("modules")
    void apiDoesNotLeakInternals(String module) {
        noClasses().that().resideInAPackage("com.cep." + module + ".api..")
                .should().dependOnClassesThat().resideInAPackage("com.cep." + module + ".internal..")
                .allowEmptyShould(true) // composition-only modules (app) have no api package
                .check(classes);
    }

    /** The shared kernel must never depend on a business module. */
    @org.junit.jupiter.api.Test
    void sharedDependsOnNoBusinessModule() {
        noClasses().that().resideInAPackage("com.cep.shared..")
                .should().dependOnClassesThat().resideInAnyPackage("com.cep.auth..", "com.cep.app..")
                .check(classes);
    }
}
