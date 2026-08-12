# R8 rules (currently inactive — minification is disabled in v1.0.3 pending
# the on-device IME-registration investigation; see app/build.gradle.kts).
#
# When re-enabled: no custom keeps are needed — OkHttp, Kotlin, coroutines
# and AndroidX ship their own consumer rules, the app uses no reflection,
# and manifest-referenced components (activities, the IME service, the
# activity-alias) are kept automatically by AGP/R8.
