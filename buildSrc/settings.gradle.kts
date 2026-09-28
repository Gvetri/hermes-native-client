// The build logic uses the same version catalog as the main build, so the Kover and PIT plugin
// versions have one declaration.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
