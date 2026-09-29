import os

_CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(os.path.dirname(_CURRENT_DIR))
PLAYER_TRACKER_PATH = os.path.normpath(os.path.join(ROOT_DIR, "player_bytetrack.yaml"))  # player BoT-SORT config
BALL_TRACKER_PATH = os.path.normpath(os.path.join(ROOT_DIR, "ball_bytetrack.yaml"))  # ball BoT-SORT config
MODEL_PATH = os.path.normpath(os.path.join(ROOT_DIR, "models", "best-Yolol26-0.929-0.636_openvino_model"))
COURT_MODEL_PATH = os.path.normpath(os.path.join(ROOT_DIR, "models", "best-CourtDetection-28-09-26_openvino_model"))

# YOLO and tracking limits
BALL_CLASS_ID = 0
HOOP_CLASS_ID = 1 
PLAYER_CLASS_ID = 2
EDGE_MARGIN = 4
MIN_ASPECT = 1.6
MAX_ASPECT = 4.5
MIN_HEIGHT = 80

# ReID and crop limits
CROP_WIDTH = 128
CROP_HEIGHT = 256
TOP_K_CROPS = 15
MIN_CROP_GAP_FRAMES = 3
OVERLAP_PENALTY_IOU = 0.3
REID_REFRESH_SECONDS = 0.5
IDEAL_NORM = 0.15
CURVE = 45.0
OVERLAP_PENALTY_IOU = 0.15