// Engine contracts used by the Android app, plus adapters over the core modules.
dependencies {
    api(project(":core:model"))
    implementation(project(":core:parser"))
    implementation(project(":core:fx"))
    implementation(project(":core:dedup"))
    implementation(project(":core:audit"))
    implementation(project(":core:budget"))
    implementation(project(":core:insights"))
}
