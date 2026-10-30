plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
val deleteBuildRoot = file("../delete/dev-busan-build")
allprojects {
    layout.buildDirectory.set(
        deleteBuildRoot.resolve(path.removePrefix(":").replace(':', '-').ifBlank { "root" })
    )
}
