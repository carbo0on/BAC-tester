plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.3"
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

// The plain `jar` task produces a THIN jar without sqlite-jdbc/gson, which fails
// to load in Burp (NoClassDefFoundError: org.sqlite.JDBC). Chain it to shadowJar
// so the documented `./gradlew jar` always yields the loadable fat jar
// at build/libs/bac-timemachine.jar.
tasks.named("jar") {
    finalizedBy(tasks.named("shadowJar"))
}
