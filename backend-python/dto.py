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
    mapped_x: float = 0.0   # 2D court x (cm) from the Java homography, 0 when unmapped
    mapped_y: float = 0.0   # 2D court y (cm) from the Java homography, 0 when unmapped

class DetectionData(BaseModel):
    frameIndex: int
    classId: int
    x: float
    y: float
    width: float
    height: float
    confidence: float
    trackId: int = -1

class BallFrameState(BaseModel):
    frameIndex: int
    label: str 

class RenderRequest(BaseModel):
    videoPath: str
    shots: list[ShotData]
    detections: list[DetectionData]
    playerIds: list[int] = []
    ballStates: list[BallFrameState] = []
