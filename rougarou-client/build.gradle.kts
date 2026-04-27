description = "Rougarou client: SDK and HTTP gateway for embedding the harness"

dependencies {
    api(project(":rougarou-core"))
    api(project(":rougarou-gateway"))
    api("io.javalin:javalin:6.4.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
}
