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
}

dependencies {
    // Akka dependencies
    implementation("com.typesafe.akka:akka-actor-typed_2.13:2.9.0-M2")
    implementation("com.typesafe.akka:akka-stream_2.13:2.9.0-M2")
    implementation("com.typesafe.akka:akka-slf4j_2.13:2.9.0-M2")

    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // Http lib
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    //Open AI client
    implementation("com.theokanning.openai-gpt3-java:service:0.18.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.0")

    //Jsoup
    implementation("org.jsoup:jsoup:1.19.1")

    // Testing
    testImplementation("com.typesafe.akka:akka-actor-testkit-typed_2.13:2.9.0-M2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

// Sets up environment variables
tasks.register("runSystem") {
    doLast {
        exec {
            file("src/main/resources/.env").readLines().forEach { line ->
                if (line.contains("=")) {
                    val (key, value) = line.split("=", limit = 2)
                    environment(key, value)
                }
            }

//            commandLine("./gradlew", "run")

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

tasks.test {
    useJUnitPlatform()
}