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
