plugins {
	java
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.oddsalchemist"
version = "0.0.1-SNAPSHOT"
description = "A personal backend service for real-time horse racing odds monitoring and anomaly detection."

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jsoup:jsoup:1.17.2")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Google Sheets API
    implementation("com.google.api-client:google-api-client:2.4.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20230815-2.0.0")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
