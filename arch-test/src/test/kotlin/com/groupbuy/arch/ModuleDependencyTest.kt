package com.groupbuy.arch

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * 모듈 간 의존 규칙. 설계서 5.3.
 *
 * 허용 방향:  deal ← participation ← payment
 * 이벤트 전용: settlement, realtime, search (비즈니스 모듈을 직접 호출하지 않음)
 * 공통:       common 은 누구나 의존 가능
 */
@AnalyzeClasses(
    packages = ["com.groupbuy"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ModuleDependencyTest {

    companion object {
        const val COMMON = "com.groupbuy.common.."
        const val DEAL = "com.groupbuy.deal.."
        const val PARTICIPATION = "com.groupbuy.participation.."
        const val PAYMENT = "com.groupbuy.payment.."
        const val SETTLEMENT = "com.groupbuy.settlement.."
        const val REALTIME = "com.groupbuy.realtime.."
        const val SEARCH = "com.groupbuy.search.."
        const val API_APP = "com.groupbuy.api.."
        const val WORKER_APP = "com.groupbuy.worker.."

        val BUSINESS_MODULES = arrayOf(DEAL, PARTICIPATION, PAYMENT, SETTLEMENT, REALTIME, SEARCH)
    }

    @ArchTest
    @JvmField
    val `deal 은 다른 비즈니스 모듈에 의존하지 않는다`: ArchRule =
        noClasses().that().resideInAPackage(DEAL)
            .should().dependOnClassesThat().resideInAnyPackage(PARTICIPATION, PAYMENT, SETTLEMENT, REALTIME, SEARCH)

    @ArchTest
    @JvmField
    val `participation 은 deal 에만 의존한다`: ArchRule =
        noClasses().that().resideInAPackage(PARTICIPATION)
            .should().dependOnClassesThat().resideInAnyPackage(PAYMENT, SETTLEMENT, REALTIME, SEARCH)

    @ArchTest
    @JvmField
    val `payment 는 deal, participation 에만 의존한다`: ArchRule =
        noClasses().that().resideInAPackage(PAYMENT)
            .should().dependOnClassesThat().resideInAnyPackage(SETTLEMENT, REALTIME, SEARCH)

    @ArchTest
    @JvmField
    val `이벤트 전용 모듈은 비즈니스 모듈을 직접 호출하지 않는다`: ArchRule =
        noClasses().that().resideInAnyPackage(SETTLEMENT, REALTIME, SEARCH)
            .should().dependOnClassesThat().resideInAnyPackage(DEAL, PARTICIPATION, PAYMENT)
            .allowEmptyShould(true)   // Phase 4~5 전까지 이 모듈들은 비어 있다

    @ArchTest
    @JvmField
    val `settlement 은 다른 이벤트 전용 모듈에 의존하지 않는다`: ArchRule =
        noClasses().that().resideInAPackage(SETTLEMENT)
            .should().dependOnClassesThat().resideInAnyPackage(REALTIME, SEARCH)
            .allowEmptyShould(true)

    @ArchTest
    @JvmField
    val `realtime 은 다른 이벤트 전용 모듈에 의존하지 않는다`: ArchRule =
        noClasses().that().resideInAPackage(REALTIME)
            .should().dependOnClassesThat().resideInAnyPackage(SETTLEMENT, SEARCH)
            .allowEmptyShould(true)

    @ArchTest
    @JvmField
    val `search 는 다른 이벤트 전용 모듈에 의존하지 않는다`: ArchRule =
        noClasses().that().resideInAPackage(SEARCH)
            .should().dependOnClassesThat().resideInAnyPackage(SETTLEMENT, REALTIME)
            .allowEmptyShould(true)

    @ArchTest
    @JvmField
    val `common 은 어떤 비즈니스 모듈에도 의존하지 않는다`: ArchRule =
        noClasses().that().resideInAPackage(COMMON)
            .should().dependOnClassesThat().resideInAnyPackage(*BUSINESS_MODULES, API_APP, WORKER_APP)

    @ArchTest
    @JvmField
    val `모듈은 apps 코드를 참조하지 않는다`: ArchRule =
        noClasses().that().resideInAnyPackage(COMMON, *BUSINESS_MODULES)
            .should().dependOnClassesThat().resideInAnyPackage(API_APP, WORKER_APP)

    @ArchTest
    @JvmField
    val `모듈 간 순환 의존이 없다`: ArchRule =
        slices().matching("com.groupbuy.(*)..").should().beFreeOfCycles()
}
