package org.kodein.mock.gradle

import com.android.build.api.dsl.TestedExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.google.devtools.ksp.gradle.KspExtension
import java.util.Locale
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestFramework
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet


public class MocKMPGradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.add("mockmp", Extension(target))
    }

    public enum class Helper { AutoDetect, JUnit4, JUnit5 }

    public class Options {
        internal var helper: Helper? = null
        internal var throwErrors: Boolean = false
        internal var public: Boolean = false
        internal var accessorsPackage: String = "org.kodein.mock.generated"
        internal var specificTargets: Set<String>? = null

        public val junit4: Helper = Helper.JUnit4
        public val junit5: Helper = Helper.JUnit5

        @JvmOverloads
        public fun withHelper(helper: Helper = Helper.AutoDetect) { this.helper = helper }

        @JvmOverloads
        public fun throwErrors(throwErrors: Boolean = true) { this.throwErrors = throwErrors }

        @JvmOverloads
        public fun public(public: Boolean = true) { this.public = public }

        public fun accessorsPackage(accessorsPackage: String) { this.accessorsPackage = accessorsPackage }

        public fun targets(vararg targets: String) { specificTargets = targets.toSet() }
        public fun allTargets() { specificTargets = null }
    }

    public class Extension(private val project: Project) {
        @JvmOverloads
        public fun onTest(confOptions: Action<Options>? = null) {
            val options = Options()
            confOptions?.execute(options)

            val kotlin =
                project.extensions.findByName("kotlin") as? KotlinProjectExtension
                    ?: error("MocKMP could not find the Kotlin plugin")

            val commonSourceSet = kotlin.sourceSets[if (kotlin is KotlinMultiplatformExtension) "commonTest" else "test"]

            addRuntimeDependencies(
                commonSourceSet = commonSourceSet,
                helper = options.helper,
                canAutodetect = true
            )

            val ksp = project.extensions.findByType<KspExtension>()
                ?: error("MocKMP could not find the KSP plugin")

            configureKspProcessor(
                ksp = ksp,
                options = options,
                multiplatform = kotlin is KotlinMultiplatformExtension
            )

            val extract = registerExtractTask(
                kotlin = kotlin,
                commonSourceSet = commonSourceSet,
                options = options,
            )
            if (kotlin is KotlinAndroidProjectExtension) {
                val android: TestedExtension = project.extensions.findByName("android") as? TestedExtension
                    ?: error("MocKMP could not find the Android plugin")
                val variants = when (android) {
                    is LibraryExtension -> android.libraryVariants
                    is BaseAppModuleExtension -> android.applicationVariants
                    else -> error("Unknown 'android' extension $android (neither library nor application)")
                }
                variants.all {
                    testVariant?.registerJavaGeneratingTask(extract, extract.get().outputDirectory.get().asFile)
                    unitTestVariant?.registerJavaGeneratingTask(extract, extract.get().outputDirectory.get().asFile)
                }
            } else {
                commonSourceSet.kotlin.srcDir(extract)
            }

            addKspDependencies(
                kotlin = kotlin,
                suffix = "Test",
                options = options,
            )
        }

        @JvmOverloads
        public fun onMain(confOptions: Action<Options>? = null) {
            val options = Options()
            confOptions?.execute(options)

            val kotlin =
                project.extensions.findByName("kotlin") as? KotlinProjectExtension
                    ?: error("MocKMP could not find the Kotlin plugin")

            val commonSourceSet = kotlin.sourceSets[if (kotlin is KotlinMultiplatformExtension) "commonMain" else "main"]

            addRuntimeDependencies(
                commonSourceSet = commonSourceSet,
                helper = options.helper,
                canAutodetect = false
            )

            val ksp = project.extensions.findByType<KspExtension>()
                ?: error("MocKMP could not find the KSP plugin")

            configureKspProcessor(
                ksp = ksp,
                options = options,
                multiplatform = kotlin is KotlinMultiplatformExtension
            )

            val extract = registerExtractTask(
                kotlin = kotlin,
                commonSourceSet = commonSourceSet,
                options = options,
            )
            if (kotlin is KotlinAndroidProjectExtension) {
                val android: TestedExtension = project.extensions.findByName("android") as? TestedExtension
                    ?: error("MocKMP could not find the Android plugin")
                val variants = when (android) {
                    is LibraryExtension -> android.libraryVariants
                    is BaseAppModuleExtension -> android.applicationVariants
                    else -> error("Unknown 'android' extension $android (neither library nor application)")
                }
                variants.all {
                    registerJavaGeneratingTask(extract, extract.get().outputDirectory.get().asFile)
                }
            } else {
                commonSourceSet.kotlin.srcDir(extract)
            }

            addKspDependencies(
                kotlin = kotlin,
                suffix = "",
                options = options,
            )
        }

        private fun addRuntimeDependencies(
            commonSourceSet: KotlinSourceSet,
            helper: Helper?,
            canAutodetect: Boolean,
        ) {
            commonSourceSet.dependencies {
                implementation("org.kodein.mock:mockmp-runtime:${BuildConfig.VERSION}")
                if (helper != null) {
                    implementation(project.provider {
                        val isJunit5 = when (helper) {
                            Helper.JUnit4 -> false
                            Helper.JUnit5 -> true
                            Helper.AutoDetect -> {
                                if (canAutodetect) {
                                    project.tasks.withType<Test>().any { it.testFramework is JUnitPlatformTestFramework }
                                } else {
                                    error("MocKMP cannot auto-detect JUnit version, please use withHelper(junit4) or withHelper(junit5)")
                                }
                            }
                        }
                        if (isJunit5) "org.kodein.mock:mockmp-test-helper-junit5:${BuildConfig.VERSION}"
                        else "org.kodein.mock:mockmp-test-helper:${BuildConfig.VERSION}"
                    })
                }
            }
        }

        private fun configureKspProcessor(
            ksp: KspExtension,
            options: Options,
            multiplatform: Boolean
        ) {
            if (options.throwErrors) {
                ksp.arg("org.kodein.mock.errors", "throw")
            }
            if (options.public) {
                ksp.arg("org.kodein.mock.visibility", "public")
            }
            ksp.arg("org.kodein.mock.package", options.accessorsPackage)
            ksp.arg("org.kodein.mock.multiplatform", multiplatform.toString())
        }

        private fun registerExtractTask(
            kotlin: KotlinProjectExtension,
            commonSourceSet: KotlinSourceSet,
            options: Options
        ): TaskProvider<MocKMPExtractExpectKt> =
            project.tasks.register<MocKMPExtractExpectKt>("mockmpExtractExpectKt") {
                outputDirectory.set(project.layout.buildDirectory.get().asFile.resolve("mockmp/${commonSourceSet.name}/kotlin"))
                accessorsPackage.set(options.accessorsPackage)
                public.set(options.public)
                resource.set("/mockmp.${if (kotlin is KotlinMultiplatformExtension) "multi" else "single"}.kt")
            }

        private fun addKspDependencies(kotlin: KotlinProjectExtension, suffix: String, options: Options) {
            val kotlinTargets = when (kotlin) {
                is KotlinMultiplatformExtension -> {
                    when (val targets = options.specificTargets) {
                        null -> kotlin.targets.filterNot { it.name == "metadata" }
                        else -> kotlin.targets.filter { it.name in targets }
                    }
                }
                is KotlinSingleTargetExtension<*> -> {
                    require(options.specificTargets == null) { "Cannot specify MocKMP targets in a single target kotlin project." }
                    listOf(kotlin.target)
                }
                else -> error("Unexpected 'kotlin' extension $kotlin")
            }

            kotlinTargets.forEach { target ->
                project.dependencies.add(
                    "ksp${target.name.capitalized()}${suffix}",
                    "org.kodein.mock:mockmp-processor:${BuildConfig.VERSION}",
                )
            }
        }
    }
}

private fun String.capitalized(locale: Locale = Locale.getDefault()) = replaceFirstChar {
    when {
        it.isLowerCase() -> it.titlecase(locale)
        else -> it.toString()
    }
}
