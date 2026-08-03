import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Upload-key credentials from `src/keystore.properties` (gitignored). Absent on any machine that
 * only builds debug — including a fresh clone, CI, and `scripts/check.sh`.
 */
val keystoreProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

/**
 * Environment wins over the file so CI never depends on a checked-out secret: the workflow decodes
 * the keystore from a GitHub secret and passes the passwords as env vars. Blank counts as absent —
 * an unfilled `keystore.properties` template would otherwise satisfy a null check and then fail
 * deep inside the signing task with "keystore password was incorrect".
 */
fun signingCredential(env: String, property: String): String? =
    (System.getenv(env) ?: keystoreProperties?.getProperty(property))?.takeIf { it.isNotBlank() }

val signingCredentials: Map<String, String?> = mapOf(
    "storeFile" to signingCredential("ANDROID_KEYSTORE_FILE", "storeFile"),
    "storePassword" to signingCredential("ANDROID_KEYSTORE_PASSWORD", "storePassword"),
    "keyAlias" to signingCredential("ANDROID_KEY_ALIAS", "keyAlias"),
    "keyPassword" to signingCredential("ANDROID_KEY_PASSWORD", "keyPassword"),
)

/**
 * This file is evaluated for every task, so a signing problem must not break `assembleDebug` or
 * `scripts/check.sh` on a machine that never signs anything. Only builds that actually produce a
 * signable artifact are held to a complete configuration — lint and test tasks are not.
 */
val buildingSignedArtifact: Boolean = gradle.startParameter.taskNames.any { task ->
    Regex("(assemble|bundle|install|publish).*release", RegexOption.IGNORE_CASE).containsMatchIn(task)
}

/**
 * None present is legitimate — a debug-only machine, a fresh clone — and simply leaves the release
 * variant unsigned. *Some* present is always a mistake, so when it would matter this names the
 * missing credential rather than letting it surface as an opaque signing-task error, or worse as a
 * silently unsigned artifact that is only rejected once uploaded.
 */
val releaseSigning: Map<String, String>? = when {
    signingCredentials.values.all { it != null } -> signingCredentials.mapValues { (_, v) -> v!! }
    signingCredentials.values.all { it == null } -> null
    buildingSignedArtifact -> throw GradleException(
        "Incomplete release signing configuration; missing: " +
            signingCredentials.filterValues { it == null }.keys.joinToString(", ") +
            ". Set all four ANDROID_* environment variables, or fill in every field of " +
            "src/keystore.properties, or remove the file entirely to build unsigned."
    )
    else -> null
}

android {
    namespace = "ch.jeanrichard.nfcspoolwriter"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Frozen forever once the first bundle is uploaded to Play — it is the store URL.
        applicationId = "ch.jeanrichard.nfcspoolwriter"
        minSdk = 29
        targetSdk = 37
        // Play rejects a duplicate versionCode, so CI supplies a fresh one per run rather than
        // relying on this file being bumped by hand before every upload. The literals below stay
        // authoritative for local builds.
        versionCode = (findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionNameOverride") as String?)?.takeIf { it.isNotBlank() }
            ?: "1.0"
    }

    signingConfigs {
        releaseSigning?.let { credentials ->
            create("release") {
                storeFile = file(credentials.getValue("storeFile"))
                storePassword = credentials.getValue("storePassword")
                keyAlias = credentials.getValue("keyAlias")
                keyPassword = credentials.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Null when no credentials were found, which leaves the bundle unsigned rather than
            // silently debug-signed. Configuration stays permissive so a debug-only machine can
            // still run lintRelease; the CI workflow asserts the bundle is actually signed.
            signingConfig = signingConfigs.findByName("release")

            // R8 stays off for the first store release. Ktor + kotlinx.serialization are the
            // classic source of release-only reflection failures, and with no crash reporting in
            // the app there is nothing to catch one in the field. Revisit once 1.0 is stable.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Off by default since AGP 8. Needed for BuildConfig.DEBUG, which keeps the development tag
        // harness out of release builds (AppNavigation).
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockk)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
