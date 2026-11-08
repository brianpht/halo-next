import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
    checkstyle
    alias(libs.plugins.spotless)
    alias(libs.plugins.jmh)
}

val targetJava = (property("targetJavaVersion") as String).toInt()

group = "io.justrade.halonext"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Agrona 2.x accesses jdk.internal.misc.Unsafe and sun.nio.ch on modern JDKs.
val agronaJvmArgs = listOf(
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
)

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJava))
    }
}

// Dedicated source set for integration tests (real temp-dir open/write/reopen cycles).
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    api(libs.agrona)
    implementation(libs.hdrhistogram)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configDirectory.set(layout.projectDirectory.dir("config/checkstyle"))
    isIgnoreFailures = false
    maxWarnings = 0
}

configure<SpotlessExtension> {
    java {
        target("src/**/*.java")
        targetExclude("**/build/**", "**/generated/**")
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
    options.compilerArgs.add("-Werror")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(agronaJvmArgs)
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events("passed", "skipped", "failed")
    }
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (real temp directory open/write/read/reopen cycles)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTestTask)
}

// Benchmarks and JMH-generated sources are not the production hot path:
// keep lint informative and skip the -Werror / checkstyle gate for the jmh source set.
jmh {
    jmhVersion.set(libs.versions.jmh.get())
    jvmArgs.set(agronaJvmArgs)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
    if (project.hasProperty("jmh.profilers")) {
        profilers.set((project.property("jmh.profilers") as String)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }
    // -PquickBench: fast smoke run used by the CI gate.
    if (project.hasProperty("quickBench")) {
        warmupIterations.set(1)
        iterations.set(1)
        fork.set(1)
    }
}

tasks.withType<JavaCompile>().matching { it.name.contains("Jmh") }.configureEach {
    options.compilerArgs.remove("-Werror")
}
tasks.withType<Checkstyle>().matching { it.name.contains("Jmh") || it.name.contains("jmh") }.configureEach {
    enabled = false
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}
