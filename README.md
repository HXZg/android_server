# Android Server

A basic Android application project.

## Project Structure

```
android_server/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/androidserver/
│   │   │   └── MainActivity.kt
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml
│   │   │   └── values/
│   │   │       ├── strings.xml
│   │   │       └── themes.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── gradle/wrapper/
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Build

```bash
./gradlew build
```

## Requirements

- Android SDK 34
- Gradle 8.2+
- Java 8+