plugins {
    // Downloads a Java 25 toolchain on machines that have none installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "trace-tail"

include("agent")
