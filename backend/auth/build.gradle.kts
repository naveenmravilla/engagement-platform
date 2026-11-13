plugins {
    id("cep.java-conventions")
}

dependencies {
    api(project(":shared"))
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55") // runtime copy comes with springdoc in :app
}
