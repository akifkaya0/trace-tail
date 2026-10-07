import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.akifkaya0"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// The agent that the plugin adds to the applications it starts; it is not on the plugin's class path.
val agent = configurations.create("agent") {
    isCanBeConsumed = false
}

dependencies {
    agent(project(":agent"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    intellijPlatform {
        local(providers.gradleProperty("platformLocalPath"))
        // Java run configurations, which receive the receiver's port, and Java classes, which logs point to.
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

// Every sandbox gets the agent: runIde prepares its own (prepareSandbox_runIde), not prepareSandbox's.
tasks.withType<PrepareSandboxTask>().configureEach {
    from(agent) {
        into(pluginName.map { "$it/agent" })
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.runIde {
    // The sandbox IDE opens the sample applications, ready to run.
    val sample = layout.projectDirectory.dir("sample").asFile.absolutePath
    argumentProviders += CommandLineArgumentProvider { listOf(sample) }
}
