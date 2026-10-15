# ProGuard/R8 rules for com.zamotkin.extrahealthgarmin.
# This file completes the release build configuration referenced from
# app/build.gradle (getDefaultProguardFile('proguard-android-optimize.txt'),
# 'proguard-rules.pro').

# Keep crash stack traces readable: retain source file names and line numbers.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# No app-specific keep rules are required: the app uses no runtime reflection
# and no serialization. The bundled consumer rules of the AndroidX libraries
# (appcompat, constraintlayout, material, lifecycle LiveData/ViewModel) and
# kotlinx-coroutines already cover everything they need.
