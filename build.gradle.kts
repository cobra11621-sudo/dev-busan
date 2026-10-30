plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
// Build directly into the cleanup area; do not duplicate APKs in the project.
subprojects {
    layout.buildDirectory.set(file("E:/ddd/delete/dev-busan-build"))
}
