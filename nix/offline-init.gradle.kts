var repositoryLocation = System.getenv("MAVEN_SOURCE_REPOSITORY")

fun RepositoryHandler.replaceRepositories() {
    clear()
    maven(repositoryLocation) {
        name = "Offline Maven Repository"
        metadataSources {
            gradleMetadata()
            mavenPom()
            artifact()
        }
    }
}

gradle.beforeSettings {
    settings.pluginManagement.repositories.replaceRepositories()
}

gradle.settingsEvaluated {
    settings.pluginManagement.repositories.replaceRepositories()
    settings.dependencyResolutionManagement.repositories.replaceRepositories()
}
