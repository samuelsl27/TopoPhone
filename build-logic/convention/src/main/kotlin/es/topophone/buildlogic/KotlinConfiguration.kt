package es.topophone.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Bytecode objetivo común (Android y JVM): Java 17. El daemon y la compilación usan JDK 21. */
internal val TARGET_JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17

internal fun Project.configureKotlinCompilation() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
