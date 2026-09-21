package com.groupbuy.arch

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture

/**
 * 모듈 내부 레이어 규칙. 모든 비즈니스 모듈에 동일하게 적용.
 *
 *   api ──► application ──► domain ◄── infrastructure
 *
 * domain 은 Spring 을 모른다. JPA 애노테이션(jakarta.persistence)은 실용상 허용한다.
 */
@AnalyzeClasses(
    packages = ["com.groupbuy"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class LayerRuleTest {

    @ArchTest
    @JvmField
    val `레이어 방향을 지킨다`: ArchRule =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Api").definedBy("..api..")
            .layer("Application").definedBy("..application..")
            .layer("Domain").definedBy("..domain..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Api")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Application", "Infrastructure")
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()

    @ArchTest
    @JvmField
    val `domain 은 Spring 프레임워크에 의존하지 않는다`: ArchRule =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
            )

    @ArchTest
    @JvmField
    val `domain 은 infrastructure 를 모른다`: ArchRule =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
}
