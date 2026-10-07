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

tasks.prepareSandbox {
    from(agent) {
        into(pluginName.map { "$it/agent" })
    }
}
