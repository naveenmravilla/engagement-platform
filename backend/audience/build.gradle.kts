plugins {
    id("cep.java-conventions")
}

dependencies {
    api(project(":shared"))
    implementation(project(":auth"))
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55") // runtime copy comes with springdoc in :app
}
