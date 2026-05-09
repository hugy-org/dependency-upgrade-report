dependencies {
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    testImplementation(project(":dependency-report-testkit"))
}
