plugins {
    `java-library`
}

group = "com.akashic.oceanwaves"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<JavaCompile>().configureEach {
    // Roda no cliente Minecraft 1.7.10 (Java 8) e no servidor Crucible (Java 21): bytecode e API do Java 8.
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}

// Medidas de desempenho do núcleo (fora do build normal): ./gradlew :core:bench
val bench by tasks.registering(JavaExec::class) {
    description = "Mede o custo da grade e das consultas pontuais."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.akashic.oceanwaves.core.Bench")
}
