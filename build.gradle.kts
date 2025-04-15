group = "com.theleoborges"
version = "1.0-SNAPSHOT"

plugins {
    application
    java
}

application {
    mainClass = "com.theleoborges.researchagent.Main"
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://repository.aspose.com/repo/")
    }
}

dependencies {
    // Command Line Arguments Parser
    implementation("commons-cli:commons-cli:1.9.0")


    // Akka dependencies
    implementation("com.typesafe.akka:akka-actor-typed_2.13:2.9.0-M2")
    implementation("com.typesafe.akka:akka-stream_2.13:2.9.0-M2")
    implementation("com.typesafe.akka:akka-slf4j_2.13:2.9.0-M2")

    // Akka HTTP
    implementation("com.typesafe.akka:akka-http_3:10.6.0-M1")
    implementation("com.typesafe.akka:akka-http-jackson_3:10.6.0-M1")
    implementation("com.typesafe.akka:akka-serialization-jackson_2.13:2.9.0-M2")

    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // Http lib
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    //Open AI client
    implementation("com.openai:openai-java:0.34.1")

    //Anthropic Client
    implementation("com.anthropic:anthropic-java:0.8.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.0")

    //Jsoup
    implementation("org.jsoup:jsoup:1.19.1")


    //MCP
    implementation("io.modelcontextprotocol.sdk:mcp:0.7.0")
    implementation("io.modelcontextprotocol.sdk:mcp-bom:0.7.0")

    //SendGrid for email sending
    implementation("com.sendgrid:sendgrid-java:4.10.3")

    // Rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // PDF Generation
    implementation("org.xhtmlrenderer:flying-saucer-pdf:9.11.4")
    implementation("com.github.librepdf:openpdf:2.0.3")
    implementation("org.commonmark:commonmark:0.20.0")


    // Testing
    testImplementation("com.typesafe.akka:akka-actor-testkit-typed_2.13:2.9.0-M2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")



}

// Sets up environment variables
tasks.register("runSystem") {
    doLast {
        exec {
            standardOutput = System.err

            file("src/main/resources/.env").readLines().forEach { line ->
                if (line.contains("=")) {
                    val (key, value) = line.split("=", limit = 2)
                    environment(key, value)
                }
            }

            // Check if args property is provided and extract arguments
            val runArgs = if (project.hasProperty("args")) {
                project.property("args").toString().split(" ").toTypedArray()
            } else {
                arrayOf()
            }

            // Pass arguments to the Java application
            val commandLineArgs = mutableListOf("./gradlew", "run")
            if (runArgs.isNotEmpty()) {
                commandLineArgs.add("--args=${runArgs.joinToString(" ")}")
            }

            commandLine(commandLineArgs)
        }
    }
}


tasks {
    val fatJar = register<Jar>("fatJar") {
        dependsOn.addAll(listOf("compileJava", "processResources")) // We need this for Gradle optimization to work
        archiveClassifier.set("standalone") // Naming the jar
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest { attributes(mapOf("Main-Class" to application.mainClass)) } // Provided we set it up in the application plugin configuration
        val sourcesMain = sourceSets.main.get()
        val contents = configurations.runtimeClasspath.get()
            .map { if (it.isDirectory) it else zipTree(it) } +
                sourcesMain.output
        from(contents)
    }
    build {
        dependsOn(fatJar) // Trigger fat jar creation during build
    }
}


tasks.test {
    useJUnitPlatform()
}