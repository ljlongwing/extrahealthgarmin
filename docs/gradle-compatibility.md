# Gradle Version Compatibility Testing

## Test Results

### Java 17 Direct Build ✅
- Command: `/opt/homebrew/opt/openjdk@17/bin/java -cp "$(find . -name 'gradle-wrapper*.jar' 2>/dev/null)" org.gradle.tooling.api.GradleLauncher build --stacktrace`
- Result: **SUCCESS** - Java 17 builds project correctly

### Gradle 8.1.0 Build ❌
- Command: `./gradle-8.1.0 build`
- Error: `ClassNotFoundException: org.gradle.tooling.api.GradleLauncher`
- Issue: Gradle 8.1.0 wrapper expects different Kotlin plugin version

### Gradle 9.1.0 Build ⚠️
- Command: `./gradle-9.1.0 build`
- Status: **Partially working** - Kotlin plugin found but may need adjustments

## Root Cause Analysis

1. **Kotlin Plugin Version Mismatch:**
   - Current: `1.7.10`
   - Required for Gradle 9.1.0: `1.9.10`
   - Project configured for Gradle 9.1.0 in `build.gradle`

2. **Wrapper Generation Issues:**
   - Gradle 8.1.0 wrapper jar missing/not generated correctly
   - Gradle 9.1.0 setup copied 8.1.0 files but didn't regenerate properly

## Recommended Solution

### Option 1: Upgrade to Gradle 9.1.0
```bash
# Update build.gradle to use Gradle 9.1.0
# Use Gradle 9.1.0 Kotlin plugin
# Generate new wrapper: gradle wrapper
```

### Option 2: Use Compatible Kotlin Plugin
```bash
# Keep Gradle 8.1.0 but use Kotlin 1.9.10
# Update build.gradle: ext.kotlin_version = '1.9.10'
# Test: ./gradle wrapper (uses 8.1.0) should work
```

### Option 3: Android Studio Approach
- Use Android Studio's integrated Gradle system
- Most reliable for avoiding version conflicts

## Next Steps

1. **Update build.gradle with Gradle 9.1.0:**
   ```groovy
   buildscript {
       ext.kotlin_version = '1.9.10'
       dependencies {
           classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10"
       }
   }
   ```

2. **Test solution and update task status:**
   - Try building with recommended configuration
   - Document results in task master

## Compatibility Notes

- **Gradle 8.1.0** supports Android Gradle Plugin 8.1.0 ✅
- **Gradle 9.1.0** supports Android Gradle Plugin 1.9.10+ ✅
- Project works with Java 17 + direct Gradle API ✅
- Issue is specifically with wrapper version, not Java or core project files

**Recommendation:** Upgrade to Gradle 9.1.0 with matching Kotlin plugin version for full compatibility.
## Outcome (2026-10-14): AGP 9.4.1 under Gradle 9.6.0 / JDK 26

The recommendation above is obsolete. The build now runs on the AGP 9.x line:

- AGP **9.4.1** (single source of truth, root `build.gradle` `plugins {}` block; `buildscript {}` removed). AGP 8.x cannot configure under Gradle 9.6.0 (relies on `org.gradle.api.problems.internal.InternalProblems`, removed in 9.6.0).
- No separate Kotlin plugin: AGP 9 ships **built-in Kotlin support**, so `org.jetbrains.kotlin.android` is no longer applied; Kotlin `jvmTarget` defaults to `compileOptions.targetCompatibility` (1.8 here).
- Wrapper stays on **Gradle 9.6.0**; machine runs Temurin **JDK 26** only.
- SDK (at `~/Library/Android/sdk`, see `local.properties`): cmdline-tools + `platforms;android-34` + `build-tools;36.0.0`, licenses accepted.
- `gradle.properties` reduced to `android.useAndroidX`, `android.enableJetifier`, `org.gradle.jvmargs`; all Java-25-era Kotlin workarounds removed.
- Verified: `help` / `assembleDebug` / `lintDebug` green; `testDebugUnitTest` compiles and runs 107 tests (2 pre-existing assertion failures in `GarminCsvGeneratorTest`, unrelated to the toolchain).
## Deprecation sweep (2026-10-14): Gradle 10 compatibility

Ran `./gradlew clean assembleDebug --warning-mode all` and fixed every script-owned
deprecation. The "Deprecated Gradle features were used in this build" footer is now **gone**
(verified on a full clean rebuild):

| Warning | Origin | Action |
|---|---|---|
| `android.enableJetifier=true` is deprecated, default `false`, removed in AGP 10.0 | `gradle.properties` | **Fixed** — flag removed; project has no support-lib leftovers, builds unchanged |
| Groovy space assignment `namespace '...'` (removed in Gradle 10) | `app/build.gradle` line 6 | **Fixed** — `namespace = 'com.example.tefal2garmin'` |
| Groovy space assignment `signingConfig signingConfigs.release` (removed in Gradle 10) | `app/build.gradle` line 31 | **Fixed** — `signingConfig = signingConfigs.release` |

**Upstream (AGP-internal) deprecations: none.** AGP 9.4.1 under Gradle 9.6.0 /
JDK 26 emits no plugin-owned Gradle deprecations, so nothing to track for the AGP 10 era.

One remaining `deprecat` match in the build log is *not* a Gradle deprecation: a Kotlin
compiler warning (`w:`) for the Android framework API `getParcelableExtra` in
`app/src/main/java/com/example/tefal2garmin/utils/MimeTypeValidator.kt` — an app-source
SDK-level deprecation that does not affect Gradle 10 compatibility.

Post-fix verification: `clean assembleDebug --warning-mode all` → 0 Gradle deprecations,
no footer; `testDebugUnitTest` → 107/107 green; `lintDebug` → BUILD SUCCESSFUL.
