description = "Rougarou gateway: sessions, agent memory, persistence (built on bayou)"

dependencies {
    api(project(":rougarou-core"))
    api(project(":rougarou-agent"))
    api("com.cajunsystems:bayou:0.2.0")
}
