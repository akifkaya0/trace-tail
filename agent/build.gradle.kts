plugins {
    java
}

repositories {
    mavenCentral()
}

// Small applications that the tests start with the agent, each on one of the logging setups below.
val testApps: SourceSet = sourceSets.create("testApps")

// The setups the agent is tested on: the oldest and the newest Logback and Log4j2 it supports, and Spring Boot on each.
val setups = mapOf(
    "logback12" to listOf("ch.qos.logback:logback-classic:1.2.13"),
    "logback15" to listOf("ch.qos.logback:logback-classic:1.5.20"),
    "log4j217" to listOf("org.apache.logging.log4j:log4j-core:2.17.2"),
    "log4j224" to listOf("org.apache.logging.log4j:log4j-core:2.24.3"),
    "bootLogback" to listOf("org.springframework.boot:spring-boot:3.5.7", "ch.qos.logback:logback-classic:1.5.20"),
    "bootLog4j2" to listOf(
        "org.springframework.boot:spring-boot:3.5.7",
        "org.apache.logging.log4j:log4j-slf4j2-impl:2.24.3",
        "org.apache.logging.log4j:log4j-core:2.24.3",
    ),
).mapValues { (name, modules) ->
    configurations.create(name) {
        isCanBeConsumed = false
    }.also {
        modules.forEach { module -> dependencies.add(name, module) }
    }
}

dependencies {
    // The oldest APIs the hooks are written against; the application brings its own version at run time.
    compileOnly("org.apache.logging.log4j:log4j-core:2.17.2")
    compileOnly("ch.qos.logback:logback-classic:1.2.13")

    // Each run of a test application brings its own versions of these.
    "testAppsCompileOnly"("org.slf4j:slf4j-api:2.0.17")
    "testAppsCompileOnly"("ch.qos.logback:logback-classic:1.2.13")
    "testAppsCompileOnly"("org.apache.logging.log4j:log4j-core:2.17.2")
    "testAppsCompileOnly"("org.springframework.boot:spring-boot:3.5.7")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.test {
    useJUnitPlatform()
    // The tests start the applications with the built agent, as the plugin does, each on the class path of its setup.
    val agent = tasks.jar.flatMap { it.archiveFile }
    val classPaths = setups.mapValues { (_, configuration) -> testApps.output + configuration }
    inputs.file(agent).withPropertyName("agent")
    classPaths.forEach { (name, files) ->
        inputs.files(files).withPropertyName("classPath.$name").withNormalizer(ClasspathNormalizer::class)
    }
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dtracetail.agent=${agent.get().asFile}") +
            classPaths.map { (name, files) -> "-Dtracetail.classPath.$name=${files.asPath}" }
    }
}
