from pydantic import BaseModel

# Data Models (DTOs)
class VideoAnalysisRequest(BaseModel):
    videoPath: str

class ShotData(BaseModel):
    frameIndex: int
    isMake: bool
    shooterTrackId: int = -1
    passerTrackId: int = -1
    assist: bool = False
    makeProbability: float = 0.0

class DetectionData(BaseModel):
    frameIndex: int
    classId: int
    x: float
    y: float
    width: float
    height: float
    confidence: float
    trackId: int = -1

class RenderRequest(BaseModel):
    videoPath: str
    shots: list[ShotData]
    detections: list[DetectionData]
    playerIds: list[int] = []
