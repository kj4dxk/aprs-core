plugins { kotlin("jvm") }
group = "org.aprsfoundation.community"
version = "0.2.0"
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
sourceSets {
    main { kotlin.srcDir("src/commonMain/kotlin") }
    test { kotlin.srcDirs("src/commonTest/kotlin", "src/jvmTest/kotlin") }
}
dependencies {
    implementation("io.ktor:ktor-network:3.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    testImplementation(kotlin("test-junit"))
}
