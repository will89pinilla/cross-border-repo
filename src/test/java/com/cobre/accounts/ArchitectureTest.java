package com.cobre.accounts;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Hexagonal dependency rules of spec §11. */
@AnalyzeClasses(packages = "com.cobre.accounts", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsIndependent = noClasses()
            .that().resideInAPackage("com.cobre.accounts.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "java.sql..", "javax.sql..", "com.cobre.accounts.adapters..")
            // TODO(T04): quitar cuando existan clases en el paquete (lo hace el humano)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDependsOnlyOnDomain = noClasses()
            .that().resideInAPackage("com.cobre.accounts.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.cobre.accounts.adapters..")
            // TODO(T04): quitar cuando existan clases en el paquete (lo hace el humano)
            .allowEmptyShould(true);

    /** Single exception (spec §11): the REST controller hands uploaded files to the file reader. */
    @ArchTest
    static final ArchRule adaptersDoNotDependOnEachOther = slices()
            .matching("com.cobre.accounts.adapters.(*).(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(
                    resideInAPackage("com.cobre.accounts.adapters.in.rest"),
                    resideInAPackage("com.cobre.accounts.adapters.in.file"))
            // TODO(T04): quitar cuando existan clases en el paquete (lo hace el humano)
            .allowEmptyShould(true);
}
