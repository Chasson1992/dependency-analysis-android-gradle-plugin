# Use cases
1. Produce a report of unused direct dependencies.
1. Produce a report of used transitive dependencies.

# How to use
1. Add to your project like any other Gradle plugin.
See https://plugins.gradle.org/plugin/com.autonomousapps.dependency-analysis for instructions.
If you want to add it to all subprojects in your build, do this:

```
// root build.gradle[.kts]
buildscript {
  dependencies {
    // Add this
    classpath "gradle.plugin.com.autonomousapps:dependency-analysis-gradle-plugin:${latest_version}"
  }
}
subprojects {
    apply plugin: "com.autonomousapps.dependency-analysis"
}
```
2. Run the task. E.g., `./gradlew :my-project:misusedDependenciesDebug`.
Or, for all projects, `./gradlew misusedDependenciesDebug`
Replace `Debug` with the variant you're interested in. 

The result of this will be two files in the `my-project/build/dependency-analysis/debug` directory:
1. unused-direct-dependencies.txt
1. used-transitive-dependencies.txt

The names, of course, relate to the use-cases described above.

# TODO
1. ABI analysis (look at return types and parameters of public methods)
1. Add plugin extension for user configuration.
Particularly, specify a list of variants to analyze (maybe)
1. Extend functionality to vanilla (non-Android) Java/Kotlin projects.
1. Correctly report View-related classes that are only referenced in XML (layout) files.
Currently the plugin reports such classes as being unused.