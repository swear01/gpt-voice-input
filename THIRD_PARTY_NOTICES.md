# Third-party notices

GPT Voice Input is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)).
This file lists bundled third-party components and their licenses.

## Runtime dependencies

| Component | License | Home |
|-----------|---------|------|
| Kotlin stdlib / kotlinx-coroutines | Apache-2.0 | https://github.com/JetBrains/kotlinx.coroutines |
| AndroidX core-ktx | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX appcompat | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX lifecycle-runtime-ktx | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| OkHttp | Apache-2.0 | https://square.github.io/okhttp/ |
| android-vad (WebRTC VAD GMM) | MIT (library) / BSD-3-Clause (WebRTC VAD algorithm) | https://github.com/gkonovalov/android-vad |

## Test-only dependencies

| Component | License |
|-----------|---------|
| JUnit 4 | EPL-2.0 |
| OkHttp MockWebServer | Apache-2.0 |
| org.json (JVM test artifact) | The JSON License (permissive) |
| Robolectric | Apache-2.0 |

## Icons

Microphone, settings (gear) and chevron vector paths are from the
[Material Design Icons](https://github.com/google/material-design-icons)
collection by Google, distributed under the Apache License 2.0.

## Note

The OpenAI API is used at runtime by the user's own device; no OpenAI
client library is bundled.
