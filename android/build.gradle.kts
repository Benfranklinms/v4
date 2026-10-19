plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Local sandbox builds can redirect outputs (CI leaves this unset).
System.getenv("APP_BUILD_DIR")?.let { dir ->
    allprojects { layout.buildDirectory.set(file("$dir/${project.name}")) }
}
