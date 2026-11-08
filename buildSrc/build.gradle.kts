plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin) // on the classpath here, applied by id in :server only
    implementation(libs.detekt.gradle.plugin)
}
