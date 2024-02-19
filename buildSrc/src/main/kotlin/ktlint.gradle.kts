plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

// TODO: use versionCatalogs.named("libs") in Gradle 8.5
val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")

ktlint {
    version = libs.findVersion("ktlint").get().toString()
    verbose = true
}
