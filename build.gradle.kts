plugins { java }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

tasks.register<Copy>("buildAndCopy") {
    dependsOn(tasks.jar)
    from(layout.buildDirectory.dir("libs"))
    into("D:/MCserver/plugins") // 네 서버 plugins 경로
}