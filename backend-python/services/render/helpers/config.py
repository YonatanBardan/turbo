import cv2

from services.analyzer.config import BALL_CLASS_ID, HOOP_CLASS_ID, PLAYER_CLASS_ID

# Output video
OUTPUT_DIR = r"C:\Users\yonat\OneDrive\Desktop\Projects\Code Name - Tourbo\Process\Basketball-Logic"
OUTPUT_SUFFIX = "_stats"
VIDEO_CODEC = "mp4v"
DEFAULT_FPS = 30.0

# Shared drawing style
FONT = cv2.FONT_HERSHEY_SIMPLEX
MARGIN_PX = 16
PANEL_PADDING_PX = 8
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)
LIGHT_GRAY = (220, 220, 220)
DIM_GRAY = (180, 180, 180)
SOFT_BLUE = (180, 220, 255)
MAKE_COLOR = (0, 220, 0)
MISS_COLOR = (0, 80, 255)

# Detection boxes
CLASS_NAMES = {BALL_CLASS_ID: "Ball", HOOP_CLASS_ID: "Hoop", PLAYER_CLASS_ID: "Player"}
CLASS_COLORS = {BALL_CLASS_ID: (0, 140, 255), HOOP_CLASS_ID: (0, 0, 255), PLAYER_CLASS_ID: (0, 255, 255)}
UNKNOWN_CLASS_COLOR = (0, 255, 0)
DETECTION_BOX_THICKNESS = 2

# Shot banner (MAKE / MISS line above the box score)
BANNER_HOLD_SECONDS = 0.6
BANNER_MIN_HOLD_FRAMES = 12

# Half-court shot chart layout
MAP_WIDTH_RATIO = 0.22      # chart width as a fraction of the video width
MAP_MIN_WIDTH_PX = 160
MAP_MAX_WIDTH_PX = 420
MAP_ALPHA = 0.9             # 1.0 = opaque chart, lower lets the video show through
MAP_BACKGROUND = WHITE
MAP_LINE_COLOR = BLACK
MARKER_RADIUS_RATIO = 0.028 # marker radius as a fraction of the chart width
MARKER_OUTLINE = BLACK

# FIBA half court in centimeters. Origin is the top-left corner of the baseline, y grows toward
# the half-court line. Court, paint and free-throw values mirror CourtHomograph.java so the mapped
# shot coordinates land on the right spot of the chart.
COURT_WIDTH_CM = 1500
COURT_HALF_LENGTH_CM = 1400
COURT_CENTER_X_CM = COURT_WIDTH_CM / 2
PAINT_LEFT_CM = 505
PAINT_RIGHT_CM = 995
PAINT_DEPTH_CM = 580
FREE_THROW_RADIUS_CM = 180
BACKBOARD_Y_CM = 120
BACKBOARD_HALF_WIDTH_CM = 90
HOOP_Y_CM = 157.5
HOOP_RADIUS_CM = 22.5
RESTRICTED_RADIUS_CM = 125
THREE_POINT_RADIUS_CM = 675
THREE_POINT_SIDE_CM = 90    # straight corner segment distance from the sideline
CENTER_CIRCLE_RADIUS_CM = 180
LANE_MARKS_Y_CM = (235, 355, 440)
LANE_MARK_LENGTH_CM = 30
DASH_LENGTH_DEG = 12
