plugins {
    application
}

dependencies {
    implementation(project(":dependency-report-core"))
    implementation("tools.jackson.core:jackson-databind:3.1.3")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.3")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:3.1.3")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

application {
    applicationName = "dependency-report"
    mainClass = "hugy.dependencyreport.cli.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
