import cv2
from ultralytics import YOLO

model = YOLO("models/best-Yolo26l-0.847-0.598-(0.6231)_openvino_model", task="detect")

# - video processing function
def process_video(file_path: str) -> tuple[float,list]:

    # - Open the video
    cap = cv2.VideoCapture(file_path)
    if not cap.isOpened():
        raise ValueError(f"OpenCV could not open the video")

    # Extract the fps of the video from the metadata
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0              # A default fps if not exist

    # Processing the video into frames one by one and not simultaneously
    boxes_data = []
    frame_index = 0

    # Process the video frame-by-frame - which each frame being processed for boxes
    while True:
        ret, frame = cap.read()
        if not ret:
            break

        # verbose=False - Stopping YOLO from printing current state to the console
        results = model(frame, verbose=False)

        # Extract all bounding boxes from the current frame
        for box in results[0].boxes:

            # Use xyxy to get (Top left and bottom right)
            xyxy = box.xyxy[0].tolist()
            x1, y1, x2, y2 = xyxy[0], xyxy[1], xyxy[2], xyxy[3]

            width = x2 - x1
            height = y2 - y1

            boxes_data.append({
                "frameIndex": int(frame_index),
                "classId": int(box.cls[0]),
                "confidence": float(box.conf[0]),
                "x": float (x1),
                "y": float(y1),
                "width": float(width),
                "height": float(height)
            })
        frame_index += 1

    cap.release()
    return fps, boxes_data