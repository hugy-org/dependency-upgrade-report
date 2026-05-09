plugins {
    application
}

dependencies {
    implementation(project(":dependency-report-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
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
