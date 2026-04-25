plugins {
    application
}

description = "Rougarou examples: end-to-end demonstrations of the three-layer harness"

dependencies {
    implementation(project(":rougarou-core"))
    implementation(project(":rougarou-agent"))
    implementation(project(":rougarou-gateway"))
    implementation(project(":rougarou-client"))
    runtimeOnly("ch.qos.logback:logback-classic:1.5.3")
}

application {
    mainClass.set("com.cajunsystems.rougarou.examples.EchoChatExample")
}
