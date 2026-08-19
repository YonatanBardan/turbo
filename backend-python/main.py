from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from ultralytics import YOLO

app = FastAPI()
model = YOLO("models/Basketball-Detection-yolo26l-0.844.pt")

class VideoAnalysisRequest(BaseModel):
    videoPath: str

@app.post("/analyze")
def analyze_video(request: VideoAnalysisRequest):

    try:
        file_path = request.videoPath

        boxes_data = process_video(file_path)

        return {
            "success": True,
            "message": "Analysis completed successfully",
            "boxes": boxes_data
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


    # - video processing function
def process_video(file_path: str) -> list:

    results = model(file_path, stream = True)
    boxes_data = []

    for frame_index, frame_result in enumerate(results):
        for box in frame_result.boxes:
            dimensions = box.xywh[0]

            boxes_data.append({
                "frameIndex": int(frame_index),
                "classId": int(box.cls[0]),
                "confidence": float(box.conf[0]),
                "x": float(dimensions[0]),
                "y": float(dimensions[1]),
                "width": float(dimensions[2]),
                "height": float(dimensions[3])
            })
    return boxes_data