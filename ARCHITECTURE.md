# Doctor Fit Gym — Architecture & Implementation Guide

## Project Overview
Android squat detection app using **YOLOv8-Pose TFLite** + **CNN TFLite** for real-time form analysis.

---

## File Structure Created

```
app/src/main/
├── AndroidManifest.xml           DONE: Camera permissions, SplashScreen launcher
├── assets/
│   ├── yolov8n_pose.tflite      ADD YOUR MODEL HERE
│   └── squat_classifier.tflite  ADD YOUR MODEL HERE
├── java/com/modul/gymai/
│   ├── SplashScreen.kt           DONE: 2-second splash -> MainActivity
│   ├── MainActivity.kt           DONE: NavController + BottomNavigation
│   ├── camera/CameraManager.kt   DONE: CameraX Preview + ImageAnalysis
│   ├── pose/
│   │   ├── Keypoint.kt           DONE: COCO 17-kp data classes
│   │   └── YoloPoseEstimator.kt  DONE: YOLOv8-Pose TFLite inference
│   ├── processing/
│   │   ├── FeatureExtractor.kt   DONE: 17 kp -> 51-dim FloatArray
│   │   ├── SequenceBuffer.kt     DONE: Sliding window 96 frames
│   │   ├── SquatClassifier.kt    DONE: CNN [1,96,51] -> [SALAH,BENAR]
│   │   ├── SquatRuleEngine.kt    DONE: Biomechanical rule override
│   │   └── RepetitionCounter.kt  DONE: State machine UP/DOWN knee angle
│   ├── overlay/OverlayView.kt    DONE: Canvas skeleton (green/red)
│   ├── data/ (Room DB)           DONE: Entity, DAO, Database, Repository
│   ├── ui/
│   │   ├── home/                 DONE: HomeFragment + ViewModel
│   │   ├── exercise/             DONE: ExerciseFragment
│   │   ├── guide/                DONE: GuideFragment (4 steps)
│   │   ├── history/              DONE: HistoryFragment + ViewModel + Adapter
│   │   ├── profile/              DONE: ProfileFragment
│   │   └── detection/            DONE: DetectionFragment (full pipeline)
│   └── utils/AngleUtils.kt       DONE: Geometry helpers
└── res/
    ├── layout/  (9 layouts)      DONE
    ├── navigation/nav_graph.xml  DONE
    ├── menu/bottom_nav_menu.xml  DONE
    ├── drawable/ (10+ drawables) DONE
    └── values/ (colors, strings, themes) DONE
```

---

## ML Pipeline Flow

CameraX Frame
  -> YoloPoseEstimator (every frame)
  -> 17 Keypoints
  -> FeatureExtractor (51-dim FloatArray)
  -> SequenceBuffer (96-frame sliding window)
  -> When full: SquatClassifier CNN [1,96,51] -> [SALAH,BENAR]
  -> SquatRuleEngine (biomechanical override)
  -> Final label BENAR or SALAH
  -> RepetitionCounter (UP/DOWN state machine)
  -> Update UI + OverlayView

---

## Dependencies Added (libs.versions.toml)

| Library | Version | Purpose |
|---------|---------|---------|
| CameraX | 1.4.2 | Camera preview + frame capture |
| TensorFlow Lite | 0.4.4 | TFLite inference |
| TFLite GPU | 2.15.0 | GPU delegate |
| Navigation | 2.7.7 | Fragment navigation |
| Room | 2.6.1 | Local database |
| Lifecycle (ViewModel, LiveData) | 2.8.7 | MVVM |

---

## REQUIRED: Add TFLite Model Files

Place these in: app/src/main/assets/

### yolov8n_pose.tflite
Export from Python:
  from ultralytics import YOLO
  model = YOLO("yolov8n-pose.pt")
  model.export(format="tflite", imgsz=640, half=False)

Expected tensors:
  Input:  [1, 640, 640, 3]  float32
  Output: [1, 56, 8400]     float32

### squat_classifier.tflite
Export Keras CNN:
  converter = tf.lite.TFLiteConverter.from_keras_model(model)
  tflite_model = converter.convert()

Expected tensors:
  Input:  [1, 96, 51]  float32
  Output: [1, 2]       float32  (index0=SALAH, index1=BENAR)

---

## Rule Engine Thresholds

| Rule | Threshold | Override Action |
|------|-----------|----------------|
| Back angle | > 45 deg | Force SALAH, "Jaga punggung lurus" |
| Heel stability | Ankle above knee | Force SALAH, "Jaga tumit di lantai" |
| Keypoint visibility | conf < 0.4 | Force SALAH, "Posisikan tubuh" |

## Repetition Counter (State Machine)

  UP  ---[knee_angle < 100]---> DOWN
  DOWN ---[knee_angle > 150]---> UP + reps++

---

## Theme Colors

| Color | Hex | Usage |
|-------|-----|-------|
| primary | #C1121F | Buttons, header, nav active |
| background | #F8F9FA | Screen backgrounds |
| success | #22C55E | BENAR label, correct skeleton |
| error | #EF4444 | SALAH label, incorrect skeleton |
| accent | #FF6B35 | Keypoints, gradient |
