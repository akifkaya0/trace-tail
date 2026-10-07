package io.github.akifkaya0.tracetail

import com.intellij.execution.Executor
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.JavaProgramPatcher
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Adds the Trace Tail agent to every Java run and debug session, with the receiver's port and the
 * run configuration's name. The agent sends the application's Logback or Log4j2 events to the
 * receiver; the application itself needs no change.
 */
class TraceTailProgramPatcher : JavaProgramPatcher() {

    override fun patchJavaParameters(executor: Executor, configuration: RunProfile, javaParameters: JavaParameters) {
        val runConfiguration = configuration as? RunConfiguration ?: return
        val jar = agentJar ?: return
        val port = runConfiguration.project.service<TraceTailServer>().port
        // The agent splits its arguments at the first comma, so the name may hold commas.
        javaParameters.vmParametersList.add("-javaagent:$jar=$port,${runConfiguration.name}")
    }

    private companion object {
        val LOG = logger<TraceTailProgramPatcher>()

        /** The build puts the agent beside the plugin's jars, in its own folder. */
        val agentJar: Path? by lazy {
            val jar = PluginManager.getPluginByClass(TraceTailProgramPatcher::class.java)?.pluginPath?.resolve("agent/trace-tail-agent.jar")
            if (jar == null || !Files.isRegularFile(jar)) {
                LOG.warn("Trace Tail agent not found at $jar; applications will not send their logs")
                null
            } else {
                jar
            }
        }
    }
}
