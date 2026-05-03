plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("com.vanniktech.maven.publish") version "0.33.0" apply false
}

repositories {
    mavenCentral()
}

subprojects {
    repositories {
        mavenCentral()
    }
}
