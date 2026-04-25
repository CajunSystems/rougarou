description = "Rougarou gateway: sessions, agent memory, persistence (built on bayou + boudin)"

dependencies {
    api(project(":rougarou-core"))
    api(project(":rougarou-agent"))
    api("com.cajunsystems:bayou:0.2.0")
    api("com.cajunsystems:boudin:0.1.0")
}
