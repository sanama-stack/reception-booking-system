plugins {
    // Lets the Java toolchain be provisioned automatically when no JDK 21 is installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "reception"
