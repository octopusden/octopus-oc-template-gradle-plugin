package org.octopusden.octopus.oc.template.plugins.gradle.service

class OcTemplateServiceDependencyGraph {

    private val adjacency = mutableMapOf<String, MutableList<String>>()

    fun add(name: String, dependsOn: List<String>) {
        adjacency.getOrPut(name) { mutableListOf() }.addAll(dependsOn)
        dependsOn.forEach { adjacency.putIfAbsent(it, mutableListOf()) }
    }

    fun getOrdered(): List<String> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<String>()

        fun visit(name: String) {
            if (name !in visited) {
                visited.add(name)
                adjacency[name]?.forEach { visit(it) }
                result.add(name)
            }
        }

        adjacency.keys.forEach { visit(it) }
        return result
    }

}