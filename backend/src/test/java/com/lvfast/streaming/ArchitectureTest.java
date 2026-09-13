package com.lvfast.streaming;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private final ClassFileImporter productionImporter = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);

    private final JavaClasses applicationClasses = productionImporter
            .importPackages("com.lvfast.streaming");

    private final JavaClasses moduleClasses = productionImporter.importPackages(
            "com.lvfast.streaming.identity",
            "com.lvfast.streaming.catalog",
            "com.lvfast.streaming.library",
            "com.lvfast.streaming.playback",
            "com.lvfast.streaming.administration",
            "com.lvfast.streaming.audit",
            "com.lvfast.streaming.media",
            "com.lvfast.streaming.messaging");

    @Test
    void containsTheApprovedModules() {
        for (String module : new String[] {
            "Identity", "Catalog", "Library", "Playback", "Administration", "Audit", "Media"
        }) {
            classes()
                    .that().haveSimpleName(module + "Module")
                    .should().resideInAPackage(".." + module.toLowerCase() + "..")
                    .allowEmptyShould(false)
                    .check(applicationClasses);
        }
    }

    @Test
    void modulePackagesAreFreeOfCycles() {
        slices()
                .matching("com.lvfast.streaming.(*)..")
                .should().beFreeOfCycles()
                .check(moduleClasses);
    }
}
