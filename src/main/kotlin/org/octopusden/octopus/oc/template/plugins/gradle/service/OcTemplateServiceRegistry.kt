package org.octopusden.octopus.oc.template.plugins.gradle.service

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.octopusden.octopus.oc.template.plugins.gradle.service.dto.OcTemplateServiceParametersDTO
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.inject.Inject

open class OcTemplateServiceRegistry @Inject constructor (
    private val project: Project
) {
    private val services = mutableMapOf<String, Provider<OcTemplateService>>()
    private val logger: Logger = LoggerFactory.getLogger(OcTemplateServiceRegistry::class.java)

    fun register(name: String, config: OcTemplateServiceParametersDTO): Provider<OcTemplateService> {
        val buildServiceName = "ocTemplateService_$name"
        val existing = project.gradle.sharedServices.registrations.findByName(buildServiceName)

        if (existing == null) logger.info("Register ocTemplateService: $buildServiceName")

        return services.getOrPut(name) {
            project.gradle.sharedServices.registerIfAbsent(buildServiceName, OcTemplateService::class.java) {
                with(it.parameters) {
                    serviceName.set(config.serviceName)
                    namespace.set(config.namespace)
                    workDir.set(config.workDir)
                    templateFile.set(config.templateFile)
                    templateParameters.set(config.templateParameters)
                    attempts.set(config.attempts)
                    period.set(config.period)
                }
            }
        }
    }

    fun getByName(name: String): Provider<OcTemplateService> =
        services[name]
            ?: throw GradleException("Cannot find registered service '$name'. It may be disabled or misconfigured.")
}