pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "MediaHarvester_Android"
include(":app", ":domain", ":downloader", ":extractor", ":database", ":storage", ":data", ":ui")
