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

// Agrona 2.x accesses jdk.internal.misc.Unsafe and sun.nio.ch on modern JDKs;
// Crc32c reflects into java.util.zip.CRC32C for the hardware-accelerated path.
val agronaJvmArgs = listOf(
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.zip=ALL-UNNAMED",
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

// Dedicated source set for the macro benchmark harness. It is a cold-path driver
// that may allocate freely, so it is excluded from the -Werror and checkstyle
// hot-path gates (same treatment as the jmh source set).
val benchmark: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations["benchmarkImplementation"].extendsFrom(configurations.implementation.get())
configurations["benchmarkRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

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

tasks.withType<JavaCompile>().matching { it.name.contains("Jmh") || it.name.contains("Benchmark") }.configureEach {
    options.compilerArgs.remove("-Werror")
}
tasks.withType<Checkstyle>().matching {
    it.name.contains("Jmh") || it.name.contains("jmh") || it.name.contains("Benchmark")
}.configureEach {
    enabled = false
}

// Fails if a hot-path benchmark breaches its absolute latency budget (ADR 0003).
// Gates on the documented budget, not raw deltas, so a noisy quickBench run does
// not produce flaky failures. Only benchmarks present in the results are checked.
tasks.register("jmhBudgetCheck") {
    description = "Verifies hot-path JMH benchmarks stay within the ADR 0003 latency budget."
    group = "verification"
    dependsOn("jmh")
    val resultsFile = layout.buildDirectory.file("results/jmh/results.json")
    doLast {
        val budgetsNs = mapOf(
            "decodeHeader" to 100.0,
            "encodeRecord" to 100.0,
            "encodeIndexEntry" to 100.0,
            "encodeTombstone" to 100.0,
            "lookupHit" to 200.0,
            "lookupMiss" to 200.0,
            "indexInsert" to 300.0,
            "indexReplace" to 300.0,
            "get" to 10_000.0,
            "getMiss" to 1_000.0,
            "put" to 5_000.0,
            "delete" to 5_000.0,
        )
        val file = resultsFile.get().asFile
        if (!file.exists()) {
            throw GradleException("JMH results not found at ${file.path}; run the jmh task first.")
        }
        @Suppress("UNCHECKED_CAST")
        val results = groovy.json.JsonSlurper().parse(file) as List<Map<String, Any>>
        val breaches = mutableListOf<String>()
        results.forEach { entry ->
            val name = (entry["benchmark"] as String).substringAfterLast('.')
            val budget = budgetsNs[name] ?: return@forEach
            val metric = entry["primaryMetric"] as Map<String, Any>
            val unit = metric["scoreUnit"] as String
            val factor = when {
                unit.startsWith("ns") -> 1.0
                unit.startsWith("us") -> 1_000.0
                unit.startsWith("ms") -> 1_000_000.0
                else -> 1.0
            }
            val scoreNs = (metric["score"] as Number).toDouble() * factor
            if (scoreNs > budget) {
                breaches += "$name: ${"%.1f".format(scoreNs)} ns > budget ${budget} ns"
            }
        }
        if (breaches.isNotEmpty()) {
            throw GradleException("Hot-path latency budget exceeded (ADR 0003):\n  " + breaches.joinToString("\n  "))
        }
        logger.lifecycle("JMH budget check passed.")
    }
}

// Runs a macro benchmark workload from the dedicated benchmark source set.
// Example: ./gradlew benchmarkRun -Pworkload=READ_RANDOM -Ptier=smoke
// Not part of the per-commit gate; numbers are environment-sensitive (see ADR 0003).
tasks.register<JavaExec>("benchmarkRun") {
    description = "Runs a macro benchmark workload (-Pworkload, -Ptier, -Pdir, -Pthreads, -Preads, -PwriteRate, -Pduration)."
    group = "verification"
    dependsOn("benchmarkClasses")
    classpath = benchmark.runtimeClasspath
    mainClass.set("io.justrade.halonext.benchmark.BenchmarkRunner")
    jvmArgs(agronaJvmArgs)
    listOf("workload", "tier", "dir", "threads", "reads", "writeRate", "duration").forEach { p ->
        if (project.hasProperty(p)) {
            systemProperty("halo.bench.$p", project.property(p) as String)
        }
    }
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}
