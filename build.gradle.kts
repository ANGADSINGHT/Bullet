plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.10"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    application
}

group = "com.aznos"
version = "0.0.1"

repositories {
    mavenCentral()

    maven {
        url = uri("https://libraries.minecraft.net")
    }
}

dependencies {
    //Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")

    //Serialization
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("dev.dewy:nbt:1.5.1")

    //Kyori
    implementation("net.kyori:adventure-api:4.19.0")
    implementation("net.kyori:adventure-text-serializer-gson:4.19.0")
    implementation("net.kyori:adventure-text-minimessage:4.19.0")

    //Faster collections
    implementation("it.unimi.dsi:fastutil-core:8.5.15")

    //Logging / util
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")

    //Mojang dependencies
    implementation("com.mojang:brigadier:1.0.18")

    //Python Plugins
    implementation("black.ninia:jep:4.1.1")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$projectDir/config/detekt/detekt.yml")
}

// Exclude generated files from detekt
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude("**/world/blocks/BlockExtensions.kt")
    exclude("**/world/blocks/Block.kt")
}

application {
    mainClass = "com.aznos.MainKt"
    applicationDefaultJvmArgs = listOf(
        "-Djava.library.path=/home/angad/.local/lib/python3.10/site-packages/jep:/home/angad/.jdks/corretto-21.0.6/lib/server:/usr/lib/x86_64-linux-gnu"
    )
}

tasks.withType<JavaExec> {
    environment(mapOf(
        "LD_LIBRARY_PATH" to "/home/angad/.local/lib/python3.10/site-packages/jep:/usr/lib/x86_64-linux-gnu:/home/angad/.jdks/corretto-21.0.6/lib/server",
        "JEP_LIBRARY_PATH" to "/home/angad/.local/lib/python3.10/site-packages/jep",
        "PYTHONHOME" to "/usr",
        "PYTHONPATH" to "/home/angad/.local/lib/python3.10/site-packages"
    ))
}

tasks.named<JavaExec>("run") {
    environment("LD_LIBRARY_PATH", "/home/angad/.jdks/corretto-21.0.6/lib/server:/usr/lib:/usr/lib/x86_64-linux-gnu:/home/angad/.local/lib/python3.10/site-packages/jep")
}

tasks.register("runServer") {
    dependsOn("detekt", "run")
    group = "bullet"
}

kotlin {
    jvmToolchain(21)
}