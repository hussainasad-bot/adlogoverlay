import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.ninedtechnologies.adlogoverlay"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // The public surface touches only long-standing framework types, so a consumer is not
        // forced up to this library's compileSdk.
        aarMetadata {
            minCompileSdk = 24
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Deliberately thin. The overlay draws with framework views and framework insets and resolves
// colours with Context.getColor, so it pulls in no AndroidX at all - a consuming app's debug
// classpath gains nothing but the coroutine the logcat reader lives on.
dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // compileOnly: not on a consumer's classpath and not in the published POM. The overlay reads
    // the HOST's own copy at runtime, and the FRC tab only appears when the host has one. Every
    // Firebase reference lives in FirebaseRemoteConfigReader.kt, loaded only after a
    // Class.forName check.
    compileOnly(platform(libs.firebase.bom))
    compileOnly(libs.firebase.config)

    testImplementation(libs.junit)
}

val versionName = providers.gradleProperty("VERSION_NAME").get()
val githubRepo = providers.gradleProperty("GITHUB_REPO").orNull?.trim()?.takeIf { it.isNotEmpty() }
// Set in CI from secrets as ORG_GRADLE_PROJECT_signingInMemoryKey; locally via ~/.gradle/gradle.properties.
// Blank counts as absent: an empty CI secret would otherwise fail deep inside signing with
// "Could not read PGP secret key" instead of the clear message from checkReleaseMetadata.
val hasSigningKey = !providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank() ||
    !providers.gradleProperty("signing.keyId").orNull.isNullOrBlank()

mavenPublishing {
    coordinates("com.9dtechnologies", "adlogoverlay", versionName)

    configure(
        AndroidSingleVariantLibrary(
            // Maven Central requires a javadoc jar; KDoc lives in the sources jar.
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        )
    )

    // Uploads to the Central Portal WITHOUT releasing. Someone must press Publish on
    // central.sonatype.com - a released version can never be changed or deleted.
    publishToMavenCentral()
    // Maven Central only accepts signed artifacts, but signing needs a key. With one - always, in
    // the publish workflow - everything is signed. Without one, publishToMavenLocal still works for
    // local testing, and checkReleaseMetadata stops an unsigned upload to Central.
    if (hasSigningKey) signAllPublications()

    val repoUrl = "https://github.com/${githubRepo ?: "OWNER/adlogoverlay"}"
    pom {
        name.set("Ad Log Overlay")
        description.set(
            "A debug-only floating overlay that shows an Android app's ad events and its " +
                "Firebase Remote Config on the device, in words a tester can read."
        )
        inceptionYear.set("2026")
        url.set(repoUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("9dtechnologies")
                name.set("9D Technologies")
                url.set("https://github.com/${githubRepo?.substringBefore('/') ?: "OWNER"}")
            }
        }
        scm {
            url.set(repoUrl)
            connection.set("scm:git:git://github.com/${githubRepo ?: "OWNER/adlogoverlay"}.git")
            developerConnection.set("scm:git:ssh://git@github.com/${githubRepo ?: "OWNER/adlogoverlay"}.git")
        }
    }
}

// A release on Maven Central is permanent. Refuse to upload one whose POM links still point at a
// placeholder, or that would go up unsigned and be rejected.
val checkReleaseMetadata = tasks.register("checkReleaseMetadata") {
    val repo = githubRepo
    val signed = hasSigningKey
    doFirst {
        if (repo == null) {
            throw GradleException(
                "GITHUB_REPO is empty in gradle.properties. Set it to <owner>/<repo> before " +
                    "publishing - it becomes the permanent project link in the POM."
            )
        }
        if (!signed) {
            throw GradleException(
                "No signing key. Set signingInMemoryKey (ORG_GRADLE_PROJECT_signingInMemoryKey in " +
                    "CI) - Maven Central rejects unsigned artifacts."
            )
        }
    }
}
tasks.matching { it.name.contains("MavenCentral") }.configureEach {
    dependsOn(checkReleaseMetadata)
}
