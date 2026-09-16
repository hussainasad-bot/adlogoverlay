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

    // compileOnly: not on a consumer's classpath and not in the published POM. The overlay uses
    // the HOST's own copies at runtime, and each feature only appears when the host has the SDK.
    // Every reference lives in one file per SDK, loaded only after a Class.forName check:
    //   Firebase Remote Config -> FirebaseRemoteConfigReader.kt  (FRC tab)
    //   Google Mobile Ads      -> MobileAdsInspector.kt          (TOOLS > AD INSPECTOR)
    //   User Messaging Platform-> UmpConsentReader.kt            (TOOLS > CONSENT)
    //   AndroidX Fragment      -> AndroidxFragments.kt           (fragment on the SCREEN line)
    compileOnly(platform(libs.firebase.bom))
    compileOnly(libs.firebase.config)
    compileOnly(libs.play.services.ads.api)
    compileOnly(libs.user.messaging.platform)
    compileOnly(libs.androidx.fragment)

    testImplementation(libs.junit)
}

// ---- Versions ---------------------------------------------------------------------------------
//
// VERSION_NAME in gradle.properties is the version published. -Pbump=patch|minor|major raises it
// for this build only; publishNewVersion writes the raised value back once the upload succeeded,
// so a failed publish never leaves gradle.properties claiming a version that does not exist.
val baseVersion = providers.gradleProperty("VERSION_NAME").get()
val bump = providers.gradleProperty("bump").orNull?.trim()?.lowercase()
val versionName = if (bump == null) baseVersion else bumpVersion(baseVersion, bump)

fun bumpVersion(version: String, part: String): String {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(version)
        ?: throw GradleException("VERSION_NAME '$version' is not MAJOR.MINOR.PATCH, so it cannot be bumped")
    val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
    return when (part) {
        "major" -> "${major + 1}.0.0"
        "minor" -> "$major.${minor + 1}.0"
        "patch" -> "$major.$minor.${patch + 1}"
        else -> throw GradleException("-Pbump must be patch, minor or major, not '$part'")
    }
}

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

// ---- Publishing to GitHub Packages --------------------------------------------------------------
//
//   ./gradlew publishNewVersion                  tests, then publishes VERSION_NAME as it is
//   ./gradlew publishNewVersion -Pbump=patch     1.0.0 -> 1.0.1: tests, publishes, saves it
//   ./gradlew publishNewVersion -Pbump=minor     1.0.0 -> 1.1.0
//   ./gradlew publishNewVersion -Pbump=major     1.0.0 -> 2.0.0
//
// Credentials: gpr.user and gpr.key (a token with write:packages) in ~/.gradle/gradle.properties,
// or GITHUB_ACTOR and GITHUB_TOKEN in CI. GitHub Packages refuses to overwrite a version that
// already exists, so publishing the same version twice fails instead of replacing a release.
val githubUser = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
val githubToken = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${githubRepo ?: "OWNER/adlogoverlay"}")
            credentials {
                username = githubUser
                password = githubToken
            }
        }
    }
}

// Fail with an instruction, not a bare 401 from the registry.
val checkGitHubPublishing = tasks.register("checkGitHubPublishing") {
    val repo = githubRepo
    val hasCredentials = !githubUser.isNullOrBlank() && !githubToken.isNullOrBlank()
    doFirst {
        if (repo == null) {
            throw GradleException("GITHUB_REPO is empty in gradle.properties. Set it to <owner>/<repo>.")
        }
        if (!hasCredentials) {
            throw GradleException(
                "No GitHub credentials. Put gpr.user and gpr.key (a token with write:packages) in " +
                    "~/.gradle/gradle.properties, or set GITHUB_ACTOR and GITHUB_TOKEN."
            )
        }
    }
}
tasks.matching { it.name.contains("GitHubPackages") }.configureEach {
    dependsOn(checkGitHubPublishing)
    // Never upload a version whose tests have not passed in this same build.
    mustRunAfter("testDebugUnitTest")
}

tasks.register("publishNewVersion") {
    group = "publishing"
    description = "Runs the unit tests, then publishes VERSION_NAME (or -Pbump=patch|minor|major) to GitHub Packages."
    dependsOn("testDebugUnitTest", "publishAllPublicationsToGitHubPackagesRepository")

    val propertiesFile = rootProject.file("gradle.properties")
    val published = versionName
    val bumped = bump != null
    val packagesUrl = "https://github.com/${githubRepo ?: "OWNER/adlogoverlay"}/packages"
    doLast {
        // Runs only if the tests and the upload both succeeded.
        if (bumped) {
            val text = propertiesFile.readText()
            propertiesFile.writeText(text.replace(Regex("(?m)^VERSION_NAME=.*$"), "VERSION_NAME=$published"))
            logger.lifecycle("VERSION_NAME is now $published in gradle.properties - commit it.")
        }
        logger.lifecycle("Published com.9dtechnologies:adlogoverlay:$published -> $packagesUrl")
    }
}
