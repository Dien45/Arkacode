
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.arkacode.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arkacode.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Binary proot harus benar-benar diekstrak ke nativeLibraryDir supaya bisa
    // dieksekusi: sejak Android 10 file di folder data aplikasi tidak boleh
    // di-exec (SELinux), sedangkan nativeLibraryDir boleh.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Hasil task downloadProotBinaries (libproot.so, libtalloc.so, ...).
    sourceSets.getByName("main") {
        jniLibs.srcDir(layout.buildDirectory.dir("generated/prootJniLibs"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    // Ekstraksi tar.gz rootfs Alpine (jalur hot: runtime DistroManager).
    implementation(libs.commons.compress)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// Proot binaries (M8 — distro Alpine untuk run_command)
//
// Android tanpa root tidak boleh mengeksekusi file dari folder data aplikasi,
// jadi binary proot harus ada di dalam APK sebagai jniLibs. Task di bawah
// mengunduh paket Termux yang sudah terbukti jalan di Android (proot + talloc +
// libandroid-shmem), membongkarnya, lalu menaruh hasilnya di
// build/generated/prootJniLibs/<abi>/ sebagai lib*.so.
//
// Kalau unduhan gagal (mis. offline), build TETAP jalan: APK dibuat tanpa
// dukungan proot, dan run_command tidak akan berjalan sampai binary proot tersedia.
// Lewati total dengan: ./gradlew assembleDebug -Pproot.skip=true
// ---------------------------------------------------------------------------

/** Lewati unduhan binary proot: `./gradlew assembleDebug -Pproot.skip=true`. */
val prootSkip = (project.findProperty("proot.skip") as String?)?.toBoolean() ?: false

val downloadProotBinaries = tasks.register("downloadProotBinaries") {
    group = "arka"
    description = "Mengunduh binary proot Android (paket Termux) ke jniLibs agar run_command bisa memakai distro Alpine."
    val outputDir = layout.buildDirectory.dir("generated/prootJniLibs").get().asFile
    val cacheDir = layout.buildDirectory.dir("proot-cache").get().asFile
    outputs.dir(outputDir)
    inputs.property("prootVersion", arka.ProotBinaries.ABI_TO_ARCH.size)

    onlyIf { !prootSkip }

    doLast {
        val ready = arka.ProotBinaries.prepare(outputDir, cacheDir) { message -> logger.lifecycle(message) }
        if (ready == 0) {
            logger.warn(
                "[proot] Tidak ada binary proot yang berhasil disiapkan (offline / mirror tidak terjangkau). " +
                    "APK tetap dibangun, tapi run_command butuh binary proot (build ulang dengan jaringan).",
            )
        } else {
            logger.lifecycle("[proot] Siap untuk $ready ABI di ${outputDir.absolutePath}")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadProotBinaries)
}
