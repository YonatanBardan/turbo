import os

_CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(os.path.dirname(_CURRENT_DIR))
TRACKER_PATH = os.path.normpath(os.path.join(ROOT_DIR, "custom_bytetrack.yaml"))
MODEL_PATH = os.path.normpath(os.path.join(ROOT_DIR, "models", "best-Yolo26l-0.91-0.61_openvino_model"))

# YOLO and tracking limits
PLAYER_CLASS_ID = 2
EDGE_MARGIN = 4
MIN_ASPECT = 1.6
MAX_ASPECT = 4.5
MIN_HEIGHT = 80

# ReID and crop limits
CROP_WIDTH = 128
CROP_HEIGHT = 256
TOP_K_CROPS = 15
MIN_CROP_GAP_FRAMES = 15
OVERLAP_PENALTY_IOU = 0.3
REID_REFRESH_SECONDS = 2.0
IDEAL_NORM = 0.15
CURVE = 45.0
OVERLAP_PENALTY_IOU = 0.15