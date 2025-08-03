# MyDocumentScanner - APK Build Guide

A React Native document scanner app with OpenCV integration for Android devices.

## Prerequisites

Before building the APK, ensure you have:

- **Node.js** (v16 or higher)
- **React Native CLI** (`npm install -g react-native-cli`)
- **Android Studio** with SDK installed
- **Java Development Kit (JDK 11 or 17)**
- **Android device** connected via USB or wireless ADB

## Environment Setup

1. **Set Android SDK path** in your environment:
   ```bash
   export ANDROID_HOME=$HOME/Android/Sdk
   export PATH=$PATH:$ANDROID_HOME/emulator
   export PATH=$PATH:$ANDROID_HOME/tools
   export PATH=$PATH:$ANDROID_HOME/tools/bin
   export PATH=$PATH:$ANDROID_HOME/platform-tools
   ```

2. **Verify ADB connection**:
   ```bash
   adb devices
   ```

3. **Check device architecture**:
   ```bash
   adb shell getprop ro.product.cpu.abi
   ```

## Build Configuration

### Development Build (Faster)
For faster development builds, edit `android/gradle.properties`:
```properties
# Use only your device's architecture
reactNativeArchitectures=arm64-v8a
```

### Production Build (All Architectures)
For production APKs that work on all devices:
```properties
# Include all architectures for broader support
reactNativeArchitectures=armeabi-v7a,arm64-v8a,x86,x86_64
```

## APK Export Steps

### 1. Install Dependencies
```bash
npm install
# or
yarn install
```

### 2. Generate Release Keystore (First Time Only)
```bash
cd android/app
keytool -genkeypair -v -storetype PKCS12 -keystore my-upload-key.keystore -alias my-key-alias -keyalg RSA -keysize 2048 -validity 10000
```

### 3. Configure Signing (gradle.properties)
Add to `android/gradle.properties`:
```properties
MYAPP_UPLOAD_STORE_FILE=my-upload-key.keystore
MYAPP_UPLOAD_KEY_ALIAS=my-key-alias
MYAPP_UPLOAD_STORE_PASSWORD=*****
MYAPP_UPLOAD_KEY_PASSWORD=*****
```

### 4. Build Debug APK (Development)
```bash
# Navigate to android directory
cd android

# Build debug APK
./gradlew assembleDebug

# APK location: android/app/build/outputs/apk/debug/app-debug.apk
```

### 5. Build Release APK (Production)
```bash
# Navigate to android directory
cd android

# Clean previous builds
./gradlew clean

# Build release APK
./gradlew assembleRelease

# APK location: android/app/build/outputs/apk/release/app-release.apk
```

### 6. Install APK on Device
```bash
# Install debug APK
adb install android/app/build/outputs/apk/debug/app-debug.apk

# Install release APK
adb install android/app/build/outputs/apk/release/app-release.apk
```

## Build Commands Reference

| Command | Purpose | Output Location |
|---------|---------|-----------------|
| `./gradlew assembleDebug` | Development APK | `app/build/outputs/apk/debug/` |
| `./gradlew assembleRelease` | Production APK | `app/build/outputs/apk/release/` |
| `./gradlew bundleRelease` | Android App Bundle | `app/build/outputs/bundle/release/` |

## Architecture-Specific Builds

Build for specific architectures to reduce APK size:

```bash
# ARM 64-bit only (most modern devices)
./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a

# ARM 32-bit only (older devices)
./gradlew assembleRelease -PreactNativeArchitectures=armeabi-v7a

# x86 64-bit only (emulators/tablets)
./gradlew assembleRelease -PreactNativeArchitectures=x86_64
```

## OpenCV Integration Notes

This app includes OpenCV native libraries for:
- Document edge detection
- Image processing
- Perspective correction

The native libraries are located in:
- `android/app/src/main/jniLibs/`
- `android/opencv/native/libs/`

## Troubleshooting

### Common Issues

1. **Build fails with "Could not find OpenCV"**:
   - Ensure OpenCV module is properly included in `settings.gradle`
   - Check native library paths

2. **APK size too large**:
   - Use specific architecture builds
   - Enable APK splitting in `build.gradle`

3. **App crashes on older devices**:
   - Include `armeabi-v7a` architecture
   - Test on various Android API levels

4. **Metro bundler issues**:
   ```bash
   npx react-native start --reset-cache
   ```

5. **Gradle build fails**:
   ```bash
   cd android
   ./gradlew clean
   cd ..
   npx react-native run-android
   ```

## File Locations After Build

```
android/app/build/outputs/
├── apk/
│   ├── debug/
│   │   └── app-debug.apk           # Debug APK
│   └── release/
│       └── app-release.apk         # Release APK
└── bundle/
    └── release/
        └── app-release.aab         # Android App Bundle
```

## Performance Tips

1. **Development**: Use single architecture (`arm64-v8a`)
2. **Testing**: Build debug APK for faster iteration
3. **Production**: Use release build with all architectures
4. **Distribution**: Consider Android App Bundle (`.aab`) for Play Store

## Security Notes

- Never commit keystore files to version control
- Store keystore passwords securely
- Use different keystores for debug and release builds
- Back up your release keystore safely

---

For more information about React Native builds, visit the [official documentation](https://reactnative.dev/docs/signed-apk-android).