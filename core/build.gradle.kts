plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // só para validar o modelo de profundidade nos testes (no app usa-se onnxruntime-android)
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    systemProperty("vorticis.depthModel", rootProject.file("app/src/main/assets/models/depth_anything_v2_small_q.onnx").absolutePath)
    systemProperty("vorticis.out", layout.buildDirectory.dir("test-exports").get().asFile.absolutePath)
}
