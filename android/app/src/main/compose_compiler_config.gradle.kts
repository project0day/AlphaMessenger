// ─────────────────────────────────────────────────────────────────────────────
// BLOCK I + K — Compose compiler stability config & metrics.
//
// Paste this block into the APP module's build.gradle.kts (the one that applies
// `com.android.application` + `kotlin-android`). It configures the Kotlin compile
// tasks directly. This is the Kotlin 1.9.22 form (this project's version).
//
// IMPORTANT — version correctness:
//   The `composeCompiler { stabilityConfigurationFiles = ... }` DSL only exists in
//   Kotlin 2.0+ (org.jetbrains.kotlin.plugin.compose). On Kotlin 1.9.22 the Compose
//   compiler is configured through `freeCompilerArgs`. Using the 2.0 DSL here would
//   FAIL the build — do not switch to it unless you also upgrade Kotlin to 2.0+.
//
// What it does:
//  • stabilityConfigurationPath → tells the compiler to treat the classes listed in
//    compose_stability.conf as @Stable. This is THE fix for "ChatInfo / MessageInfo
//    unstable": those are UniFFI-generated data classes with `var` fields (regenerated
//    every build, so @Stable cannot be added to their source). Marking the package
//    stable here makes every bubble/row that reads them `restartable skippable`.
//  • metricsDestination / reportsDestination → emit the *-stability.txt,
//    *-composables.txt and *-compose_metrics reports used by BLOCK K to PROVE
//    MessageBubble / ConversationRow are `restartable skippable`.
// ─────────────────────────────────────────────────────────────────────────────
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs += listOf(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=" +
                "${project.projectDir.absolutePath}/compose_stability.conf",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" +
                "${project.buildDir.absolutePath}/compose_metrics",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
                "${project.buildDir.absolutePath}/compose_metrics"
        )
    }
}

// After `./gradlew :app:assembleRelease`, the BLOCK K proof lands in:
//   app/build/compose_metrics/app_release-composables.txt   ← restartable/skippable per @Composable
//   app/build/compose_metrics/app_release-stability.txt     ← stable/unstable per class
//
// Verify with:
//   grep -E "MessageBubble|ConversationRow|MessageUi|ChatInfo" app/build/compose_metrics/app_release-composables.txt
//   grep -E "MessageUi|ChatInfo|MessageInfo"                    app/build/compose_metrics/app_release-stability.txt
//
// PASS criteria (non-negotiable):
//   • MessageBubble and ConversationRow → "restartable skippable"
//   • MessageUi / ChatInfo / MessageInfo → "stable" in *-stability.txt
// If `skippable` is still missing, the stability report names the exact unstable
// field still poisoning it — fix THAT field, don't guess.
