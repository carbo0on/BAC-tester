plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.4")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveBaseName.set("bac-timemachine")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

// Make 'build' also produce the fat shadow jar
tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
