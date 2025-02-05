plugins {
    kodein.library.mpp
}

val copySrc by tasks.creating(Sync::class) {
    from("$projectDir/../mockmp-test-helper/src")
    into("${layout.buildDirectory.get().asFile}/src")
}

afterEvaluate {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>> {
        dependsOn(copySrc)
    }
    tasks.withType<org.gradle.jvm.tasks.Jar> {
        dependsOn(copySrc)
    }
}

kotlin.kodein {
    all()
    common.main {
        kotlin.srcDir("${layout.buildDirectory.get().asFile}/src/commonMain/kotlin")
        dependencies {
            api(projects.mockmpRuntime)
            implementation(kodeinGlobals.kotlin.test)
        }
    }

    jvm {
        sources.mainDependencies {
            implementation(kodeinGlobals.kotlin.test.junit5)
        }
    }
}

kodeinUpload {
    name = "mockmp-test-helper"
    description = "MocKMP test helper"
}
