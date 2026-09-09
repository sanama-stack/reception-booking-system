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

    // In-memory rate limiting. No Redis: one instance in MVP, and the externalisation path is
    // documented as the first scale-out task (docs/06-security.md §5, docs/02-product-architecture.md §8).
    implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")

    // Structured JSON logging (docs/06-security.md §10).
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    // E.164 phone normalisation using the business's country (docs/06-security.md §9). A
    // hand-written table of calling codes is the same mistake as a hand-written list of timezones —
    // and it could only reformat a number, never tell a real one from a typo. Load-bearing from
    // phase 06, where Customer identity is keyed on (business_id, normalised phone): a normalisation
    // that disagrees with itself splits one person into two customers.
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.7")

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

// Mockito's inline mock maker attaches an agent to its own JVM, which a future JDK will refuse —
// it already warns on every run. Passing the agent explicitly is the documented fix and means the
// suite does not start failing on a JDK upgrade.
val mockitoAgent: Configuration by configurations.creating

dependencies {
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test> {
    // Resolved at execution time rather than during configuration, which Gradle warns about.
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:" + mockitoAgent.asPath) })

    // The suite runs somewhere deliberately hostile: UTC+14, the largest offset there is, and far
    // enough east to be on tomorrow's date for ten hours of every UTC day.
    //
    // A suite that runs in UTC cannot see a timezone bug, because in UTC every conversion to and
    // from UTC is the identity. That is not hypothetical here: `hibernate.jdbc.time_zone` shifted
    // every stored Business Hour by the JVM's offset for two phases, and 416 green tests said
    // nothing, because the build server had no offset to shift by.
    //
    // Kiritimati has no daylight saving, so this is hostile without being a different environment
    // in March and October.
    systemProperty("user.timezone", "Pacific/Kiritimati")
    useJUnitPlatform {
        // Live-model tests cost money and are non-deterministic; they never gate the pipeline
        // (docs/08-testing-strategy.md §7, §10).
        //
        // -PincludeTags=llm runs them and nothing else, which is how the level-3 corpus is exercised
        // by hand before a release and after any change to the system prompt or a tool description —
        // the two things a scripted model cannot evaluate, because it reads neither. Without a key
        // the corpus skips itself rather than failing.
        if (project.hasProperty("includeTags")) {
            includeTags(project.property("includeTags") as String)
        } else {
            excludeTags("llm")
        }
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
