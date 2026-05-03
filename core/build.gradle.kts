import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

group = "io.github.abaikov"
version = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.1.20"))
    api("org.jetbrains.kotlin:kotlin-stdlib")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "cnstra-core",
        version = project.version.toString(),
    )

    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("CNStra Kotlin Core")
        description.set("Kotlin/JVM port of CNStra workflow orchestration engine")
        inceptionYear.set("2026")
        url.set("https://github.com/abaikov/cnstra-kotlin")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("abaikov")
                name.set("Andrei Baikov")
                email.set("abaikov23@gmail.com")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/abaikov/cnstra-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/abaikov/cnstra-kotlin.git")
            url.set("https://github.com/abaikov/cnstra-kotlin")
        }
    }
}
