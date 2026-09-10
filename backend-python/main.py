import traceback

import cv2
from fastapi import FastAPI, HTTPException

from dto import VideoAnalysisRequest, RenderRequest
from services.video_analyzer import process_video
from services.video_renderer import create_scoreboard_video

app = FastAPI()

# API Routes
@app.post("/analyze")
def analyze_video(request: VideoAnalysisRequest):

    try:
        fps, boxes_data, players = process_video(request.videoPath)

        return {
            "fps": fps,
            "detections": boxes_data,
            "players": players,
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/render")
def render_video(request: RenderRequest):
    try:
        output_path = create_scoreboard_video(
            request.videoPath,
            request.shots,
            request.detections,
            request.playerIds,
        )
        return {
            "outputPath": output_path,
        }
    except Exception as e:
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))