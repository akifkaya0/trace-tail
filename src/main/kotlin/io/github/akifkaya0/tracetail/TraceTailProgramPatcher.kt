package io.github.akifkaya0.tracetail

import com.intellij.execution.Executor
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.JavaProgramPatcher
import com.intellij.openapi.components.service

/**
 * Adds the receiver's port and the run configuration's name to every Java run and debug session.
 * An application whose Log4j2 configuration reacts to these properties sends its logs to Trace Tail;
 * any other application ignores them.
 */
class TraceTailProgramPatcher : JavaProgramPatcher() {

    override fun patchJavaParameters(executor: Executor, configuration: RunProfile, javaParameters: JavaParameters) {
        val runConfiguration = configuration as? RunConfiguration ?: return
        val server = runConfiguration.project.service<TraceTailServer>()
        javaParameters.vmParametersList.addProperty(PORT_PROPERTY, server.port.toString())
        javaParameters.vmParametersList.addProperty(APP_PROPERTY, runConfiguration.name)
    }

    companion object {
        const val PORT_PROPERTY = "tracetail.port"
        const val APP_PROPERTY = "tracetail.app"
    }
}
