plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.4"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "1.9.25"
}

group = "com.enzo"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
	maven { url = uri("https://repo.spring.io/milestone") }
	maven { url = uri("https://repo.spring.io/snapshot") }
}

extra["springAiVersion"] = "1.0.0"

dependencies {
	implementation(platform("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}"))
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	
	// Spring AI dependencies
	implementation("org.springframework.ai:spring-ai-ollama")
	
	// Vector database and embedding dependencies
	implementation("com.google.protobuf:protobuf-java:3.24.4")
	implementation("io.grpc:grpc-netty-shaded:1.58.0")
	implementation("io.grpc:grpc-protobuf:1.58.0")
	implementation("io.grpc:grpc-stub:1.58.0")
	implementation("io.qdrant:client:1.7.0")
	
	// HTTP client for embedding API
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	
	// Development tools
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	
	// Test dependencies
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
