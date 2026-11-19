pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.33")
}

// O projeto raiz é o mod Forge 1.7.10 (convenção GTNH). :core é a matemática pura (Java 8, sem Minecraft),
// embutida no mod e usada também por plugins Bukkit no servidor (o Expeditions).
include(":core")
