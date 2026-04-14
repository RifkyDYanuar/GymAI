Place your TFLite model files in this directory:

1. yolov8n_pose.tflite  - YOLOv8-Nano Pose model
   - Input:  [1, 640, 640, 3] float32
   - Output: [1, 56, 8400]    float32
   - Size:   ~6 MB

2. squat_classifier.tflite  - CNN Sequence classifier
   - Input:  [1, 96, 51] float32
   - Output: [1, 2]      float32  [SALAH, BENAR]
   - Size:   ~2 MB

Export from Python using:
  model.export(format='tflite', int8=False)

Then copy to:
  app/src/main/assets/
