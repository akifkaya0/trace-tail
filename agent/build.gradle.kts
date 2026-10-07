plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    // The oldest APIs the hooks are written against; the application brings its own version at run time.
    compileOnly("org.apache.logging.log4j:log4j-core:2.17.2")
    compileOnly("ch.qos.logback:logback-classic:1.2.13")
}

tasks.compileJava {
    // The agent runs inside the application, which may still be on Java 8.
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

tasks.jar {
    archiveFileName = "trace-tail-agent.jar"
    manifest {
        attributes("Premain-Class" to "io.github.akifkaya0.tracetail.agent.TraceTailAgent")
    }
}
