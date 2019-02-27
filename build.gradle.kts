import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.github.ben-manes.versions") version Plugin.VERSIONS
    idea

    kotlin("jvm") version Plugin.KOTLIN

    id("org.jetbrains.dokka") version Plugin.DOKKA
    id("com.github.spotbugs") version Plugin.SPOTBUGS_PLUGIN

    signing
    `maven-publish`

    id("com.github.johnrengelman.shadow") version Plugin.SHADOW_JAR
}

group = "com.github.bjoernpetersen"
version = "0.13.0-SNAPSHOT"

repositories {
    jcenter()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

idea {
    module {
        isDownloadJavadoc = true
    }
}

spotbugs {
    isIgnoreFailures = true
    toolVersion = Plugin.SPOTBUGS_TOOL
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks {
    "compileKotlin"(KotlinCompile::class) {
        kotlinOptions.jvmTarget = "1.8"
    }

    "compileTestKotlin"(KotlinCompile::class) {
        kotlinOptions.jvmTarget = "1.8"
    }

    "test"(Test::class) {
        useJUnitPlatform()
    }

    "dokka"(DokkaTask::class) {
        outputFormat = "html"
        outputDirectory = "$buildDir/kdoc"
    }

    @Suppress("UNUSED_VARIABLE")
    val dokkaJavadoc by creating(DokkaTask::class) {
        outputFormat = "javadoc"
        outputDirectory = "$buildDir/javadoc"
    }

    @Suppress("UNUSED_VARIABLE")
    val publishedJar by creating(Jar::class) {
        archiveBaseName.set("published-api")
        from(sourceSets["main"].output) {
            exclude("**/*Impl*", "**/META-INF/services/*")
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val javadocJar by creating(Jar::class) {
        dependsOn("dokkaJavadoc")
        archiveClassifier.set("javadoc")
        from("$buildDir/javadoc")
    }

    @Suppress("UNUSED_VARIABLE")
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    withType(Jar::class) {
        from(project.projectDir) {
            include("LICENSE")
        }
    }
}

dependencies {
    implementation(
        group = "io.github.microutils",
        name = "kotlin-logging",
        version = Lib.KOTLIN_LOGGING) {
        exclude("org.slf4j")
        exclude("org.jetbrains")
        exclude("org.jetbrains.kotlin")
    }
    compileOnly(
        group = "com.github.bjoernpetersen",
        name = "musicbot",
        version = Lib.MUSICBOT)

    implementation(
        group = "com.google.apis",
        name = "google-api-services-youtube",
        version = Lib.YOUTUBE_API) {
        exclude("com.google.guava")
    }

    testImplementation(
        group = "org.junit.jupiter",
        name = "junit-jupiter-api",
        version = Lib.JUNIT)
    testRuntime(
        group = "org.junit.jupiter",
        name = "junit-jupiter-engine",
        version = Lib.JUNIT)
}

publishing {
    publications {
        create("Maven", MavenPublication::class) {
            artifact(tasks.getByName("publishedJar"))
            artifact(tasks.getByName("javadocJar"))
            artifact(tasks.getByName("sourcesJar"))

            pom {
                name.set("YouTube Provider for MusicBot")
                description.set("Provides YouTube songs for MusicBot")
                url.set("https://github.com/BjoernPetersen/MusicBot-YouTube")

                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/BjoernPetersen/MusicBot-YouTube.git")
                    developerConnection
                        .set("scm:git:git@github.com:BjoernPetersen/MusicBot-YouTube.git")
                    url.set("https://github.com/BjoernPetersen/MusicBot-YouTube")
                }

                developers {
                    developer {
                        id.set("BjoernPetersen")
                        name.set("Björn Petersen")
                        email.set("pheasn@gmail.com")
                        url.set("https://github.com/BjoernPetersen")
                    }
                }
            }
        }
        repositories {
            maven {
                val releasesRepoUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
                val snapshotsRepoUrl = "https://oss.sonatype.org/content/repositories/snapshots"
                // change to point to your repo, e.g. http://my.org/repo
                url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl
                else releasesRepoUrl)
                credentials {
                    username = project.properties["ossrh.username"]?.toString()
                    password = project.properties["ossrh.password"]?.toString()
                }
            }
        }
    }
}

signing {
    sign(publishing.publications.getByName("Maven"))
}
