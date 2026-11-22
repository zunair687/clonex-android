# Add project specific ProGuard rules here.
# Zee Clone MVP: Room keeps its entities via @Entity annotations; the default
# Android rules already cover them. Keep this file for future rules
# (e.g. keep rules for the native virtual engine when it lands).
-keep class com.zee.clone.data.** { *; }

# v3.0: R8 (run #93) reports these library R classes as missing while
# minifying: androidx.appcompat.resources.R$dimen and
# androidx.constraintlayout.widget.R$dimen. Modern AARs ship no compiled
# R classes (only R.txt — verified: neither AAR's classes.jar contains
# any R class); the R classes are generated at build time, but R8's
# classpath check runs before they are visible to it. The resources
# themselves ship in the AARs and land in the final APK, so these
# missing R classes are safe to ignore. (R$* covers any other R inner
# class R8 may flag from the same two libraries, so this does not need
# a second round-trip.)
-dontwarn androidx.appcompat.resources.R$*
-dontwarn androidx.constraintlayout.widget.R$*

# v3.0: NewBlackbox engine — reflection-driven (hidden-API bypass, hook
# wiring, IPC callbacks invoked by reflection). Keep the whole engine
# namespace unobfuscated so the public API (BlackBoxCore, BEnvironment,
# InstallResult, ClientConfiguration) survives R8.
-keep class top.niunaijun.blackbox.** { *; }
-keepclassmembers class top.niunaijun.blackbox.** { *; }
# v3.0.6: black-reflection generated interfaces (BR* / *Static / *Context)
# carry @BClassNameNotProcess, read at runtime by BlackReflection.create().
# Without these keeps the app's R8 pass strips the annotation (and the
# interface methods) from all 96 generated interfaces — verified in the
# v3.0.5 APK dex: PackageParserMarshmallowStatic/Context had
# annotations_off=0 and class_data_off=0 — so every BR proxy fails with
# "Not found @BlackClass or @BlackStrClass" and no clone can install.
-keepattributes *Annotation*
-keep class top.niunaijun.blackreflection.** { *; }
-keep @top.niunaijun.blackreflection.annotation.BClass class * { *; }
-keep @top.niunaijun.blackreflection.annotation.BClassName class * { *; }
-keep @top.niunaijun.blackreflection.annotation.BClassNameNotProcess class * { *; }
-keep class black.** { *; }

# Bcore's own consumer-rules.pro also applies; repeat the key keeps here
# so they survive even if the consumer rules are ever dropped.
-keep class top.niunaijun.blackbox.BlackBoxCore { *; }
-keep class top.niunaijun.blackbox.entity.pm.InstallResult { *; }
-keep class top.niunaijun.blackbox.app.configuration.ClientConfiguration { *; }
-keep class top.niunaijun.blackbox.core.env.BEnvironment { *; }
