/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// This build.gradle.kts file is solely for the purpose of satisfying the linter's requirements.
// The actual build process is handled by Soong via the Android.bp
plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.8.20"

    // Apply the java-library plugin for API and implementation separation.
    `java-library`
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

sourceSets.main {
    java {
        exclude("**/*.bp")
        srcDirs("src")
    }
}

val android_build_top = System.getenv("ANDROID_BUILD_TOP") ?: "../../../../../../"
val out = "${android_build_top}/out/soong/.intermediates"

dependencies {
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")
    // Kotlin std
    implementation(files("$android_build_top/external/kotlinc/lib/kotlin-stdlib.jar"))
    // Android system SDK
    implementation(files("$android_build_top/prebuilts/sdk/33/system-server/android.jar"))
    // Framework Bluetooth
    implementation(files("$out/packages/modules/Bluetooth/framework/framework-bluetooth-pre-jarjar/android_common/turbine-combined/framework-bluetooth-pre-jarjar.jar"))
    // Pandora APIs
    implementation(files("$out/packages/modules/Bluetooth/pandora/interfaces/pandora_experimental-grpc-java/android_common/combined/pandora_experimental-grpc-java.jar"))
    implementation(files("$out/packages/modules/Bluetooth/pandora/interfaces/pandora_experimental-proto-java/android_common/combined/pandora_experimental-proto-java.jar"))
    // Junit
    implementation(files("$out/external/junit/junit/android_common/turbine-combined/junit.jar"))
    // Truth
    implementation(files("$out/external/truth/truth/android_common/turbine-combined/truth.jar"))
    // Androidx Test Core
    implementation(files("$out/prebuilts/misc/common/androidx-test/androidx.test.core/android_common/combined/androidx.test.core.jar"))
    // Androidx Test ext
    implementation(files("$out/prebuilts/misc/common/androidx-test/androidx.test.ext.junit/android_common/combined/androidx.test.ext.junit.jar"))
    // Protobuf
    implementation(files("$out/external/protobuf/libprotobuf-java-micro/android_common/turbine-combined/libprotobuf-java-micro.jar"))
    // Comptability
    implementation(files("$out/cts/common/device-side/util-axt/compatibility-device-util-axt/android_common/combined/compatibility-device-util-axt.jar"))
    // Test Parameter Injector
    implementation(files("$out/external/TestParameterInjector/TestParameterInjector/android_common/combined/TestParameterInjector.jar"))
    // TruthJava8
    implementation(files("$out/external/truth/truth-java8-extension/android_common/combined/truth-java8-extension.jar"))
    // OkHttp
    implementation(files("$out/external/grpc-grpc-java/grpc-java-okhttp-client-lite/android_common/combined/grpc-java-okhttp-client-lite.jar"))
}

