# R8 rules for the release build (minify + resource shrinking enabled).
#
# No custom keeps are needed: OkHttp, Kotlin, coroutines and AndroidX ship
# their own consumer rules, and the app uses no reflection. Manifest-referenced
# components (activities, the IME service, the activity-alias) are kept
# automatically by AGP/R8.
