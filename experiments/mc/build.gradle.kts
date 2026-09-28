plugins {
    java
}

group = "dev.vlad"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    // Есть внутри Paper 1.16.5 (build 794) — нужен только для фильтра лога паролей.
    compileOnly("org.apache.logging.log4j:log4j-core:2.17.0")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // Только выделение области топориком для приватов.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.17") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.2.17") { isTransitive = false }
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        isTransitive = false
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Paper 1.16.5 API собран под Java 8; сервер работает на 17 — 16 совместимо с обоими.
    options.release.set(16)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
