plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test {
    useJUnitPlatform()
}

// O harness de desenvolvimento (com.akashic.oceanwaves.dev) nunca vai no jar de lançamento.
tasks.named<Jar>("shadowJar") {
    exclude("com/akashic/oceanwaves/dev/**")
}

// Medidas do caminho de CPU do renderizador, sem Minecraft: ./gradlew clientBench
tasks.register<JavaExec>("clientBench") {
    description = "Mede o custo do caminho do renderizador fora do jogo (antes e depois, na mesma execução)."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.akashic.oceanwaves.client.ClientPathBench")
}
