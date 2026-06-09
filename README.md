# SLAM Engine

**SLAM Engine** is an Android visual-mapping demo that uses OpenCV ORB features, native C++ processing, and OpenGL rendering to illustrate the building blocks of monocular SLAM on a mobile device.

Written by **Dr. Vahid Tavakkoli, 2026**.

## Highlights

- **Live camera mapping** with OpenCV-backed frame processing.
- **ORB feature detection and matching** for educational visual odometry experiments.
- **Sparse 3D point-cloud visualization** rendered with OpenGL ES.
- **Camera trajectory history** with path and frustum overlays.
- **Interactive controls** for mapping duration and result-layer visibility.
- **Android native acceleration** through CMake, JNI, and C++.

## What the App Demonstrates

This project is designed as an educational SLAM prototype rather than a production navigation stack. It demonstrates how a mobile app can:

1. Capture camera frames from an Android device.
2. Extract ORB keypoints and descriptors with OpenCV.
3. Match features between consecutive frames.
4. Estimate relative camera motion from visual correspondences.
5. Triangulate sparse 3D points.
6. Render the evolving point cloud and camera path in an interactive 3D view.

## Project Structure

```text
MobileSLAM/
├── app/
│   ├── src/main/java/robotic/slam/   # Android activities, preferences, data manager, OpenGL renderer
│   ├── src/main/cpp/                 # Native OpenCV/JNI SLAM processing
│   └── src/main/res/                 # Layouts, themes, drawables, launcher assets
├── gradle/                           # Gradle wrapper and version catalog
├── build.gradle.kts                  # Root Gradle build file
├── settings.gradle.kts               # Gradle project settings
├── LICENSE                           # MIT license
└── README.md                         # Project documentation
```

## Requirements

- Android Studio with a recent Android Gradle Plugin toolchain.
- JDK 11 or newer.
- Android SDK with compile SDK 36 support.
- CMake 3.22.1.
- An Android device or emulator with camera support.

## Build and Run

1. Clone the repository:

   ```bash
   git clone <repository-url>
   cd MobileSLAM
   ```

2. Build the debug APK:

   ```bash
   ./gradlew assembleDebug
   ```

3. Install and run from Android Studio, or install the APK manually:

   ```bash
   ./gradlew installDebug
   ```

4. Grant camera permission when Android prompts for it.

## Using SLAM Engine

1. Open the app and wait for the splash screen to finish.
2. Choose the desired mapping duration from the menu.
3. Tap **Start Mapping** and slowly move the device through a textured scene.
4. Watch the live previous/current frame comparison and match overlay.
5. Open **3D Map History** to inspect the sparse point cloud, camera path, and frustum trail.
6. Toggle feature points, path, and camera frustums to focus on individual layers.

## Tips for Better Results

- Use scenes with rich visual texture, corners, and stable lighting.
- Move the device slowly to maintain feature overlap between frames.
- Avoid large motion blur, reflective surfaces, and blank walls.
- Keep a small amount of sideways movement to improve parallax for triangulation.

## Technology Stack

- **Kotlin / AndroidX** for the Android application layer.
- **Camera and OpenCV Android integration** for frame access and computer vision.
- **C++ / JNI** for native SLAM processing.
- **OpenGL ES 2.0** for 3D point-cloud and trajectory rendering.
- **Gradle Kotlin DSL** for builds and dependency management.

## Educational Scope

This application is intended for learning, research demonstrations, and experimentation with visual SLAM concepts. It is not intended for safety-critical robotics, autonomous navigation, or precision surveying without substantial validation and extension.

## License

This project is released under the MIT License. See [LICENSE](LICENSE) for details.
