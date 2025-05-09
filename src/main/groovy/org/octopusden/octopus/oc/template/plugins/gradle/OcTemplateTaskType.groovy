package org.octopusden.octopus.oc.template.plugins.gradle

enum OcTemplateTaskType {

    PROCESS("Process"),
    CREATE("Create"),
    WAIT("WaitReadiness"),
    LOGS("Logs"),
    DELETE("Delete")

    final String taskNameSuffix

    OcTemplateTaskType(String taskNameSuffix) {
        this.taskNameSuffix = taskNameSuffix
    }

    String getTaskNameSuffix() {
        return taskNameSuffix
    }

}