plugins {
    java
    id("org.springframework.boot") version "3.5.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.flywaydb.flyway") version "11.7.2"
    jacoco
}

group = "dev.reception"
version = "0.1.0"
description = "Reception — AI appointment booking SaaS"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["testcontainersVersion"] = "2.0.5"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

    // Structured JSON logging (docs/06-security.md §10).
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
}

tasks.withType<Test> {
    useJUnitPlatform {
        // Live-model tests cost money and are non-deterministic; they never gate the pipeline
        // (docs/08-testing-strategy.md §7, §10).
        excludeTags("llm")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

// Used by `make migrate`; reads the same environment variables the application does.
flyway {
    url = "jdbc:postgresql://${System.getenv("DB_HOST") ?: "localhost"}:" +
        "${System.getenv("DB_PORT") ?: "5432"}/${System.getenv("DB_NAME") ?: "reception"}"
    user = System.getenv("DB_USER") ?: "reception"
    password = System.getenv("DB_PASSWORD") ?: "local-dev-only-postgres-password"
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    validateOnMigrate = true
    cleanDisabled = true
}

// Prints the runtime classpath, so the IDE launch can be reproduced exactly from a shell.
tasks.register("printRuntimeClasspath") {
    val runtime = sourceSets["main"].runtimeClasspath
    doLast { println(runtime.asPath) }
}
