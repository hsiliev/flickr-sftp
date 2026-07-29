plugins {
    kotlin("jvm") version "2.0.0"
    application
}

group = "com.artware.flickr.sftp"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.artware.flickr.sftp.MainKt")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.apache.sshd:sshd-core:2.13.1")
    implementation("org.apache.sshd:sshd-sftp:2.13.1")
    implementation("org.apache.sshd:sshd-scp:2.13.1")
    implementation("com.flickr4java:flickr4java:3.0.8")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    implementation("org.apache.commons:commons-imaging:1.0-alpha3")

    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:5.12.0")
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}