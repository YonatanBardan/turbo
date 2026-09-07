import logging
import os

import numpy as np

logger = logging.getLogger(__name__)

_SERVICES_DIR = os.path.dirname(os.path.abspath(__file__))
WEIGHTS_PATH = os.path.normpath(
    os.path.join(_SERVICES_DIR, "..", "models", "osnet_ain_x1_0_msmt17_256x128.pth")
)
MODEL_NAME = "osnet_ain_x1_0"

_warned_missing_weights = False
_warned_extract_failed = False
_extractor = None
_extractor_checked = False


def _log_missing_weights() -> None:
    global _warned_missing_weights
    if not _warned_missing_weights:
        logger.warning("ReID weights not found at %s; appearance vectors will be empty.", WEIGHTS_PATH)
        _warned_missing_weights = True


def _log_extract_failed(exc: Exception) -> None:
    global _warned_extract_failed
    if not _warned_extract_failed:
        logger.warning("ReID extraction failed (%s); appearance vectors will be empty.", exc)
        _warned_extract_failed = True


def _get_extractor():
    global _extractor, _extractor_checked
    if _extractor_checked:
        return _extractor
    _extractor_checked = True
    try:
        try:
            from torchreid.utils import FeatureExtractor
        except ImportError:
            from torchreid.reid.utils import FeatureExtractor

        device = "cpu"
        try:
            import torch

            if torch.cuda.is_available():
                device = "cuda"
        except ImportError:
            pass

        _extractor = FeatureExtractor(
            model_name=MODEL_NAME,
            model_path=WEIGHTS_PATH,
            device=device,
        )
    except Exception as exc:
        _log_extract_failed(exc)
        _extractor = None
    return _extractor


def is_available() -> bool:
    if not os.path.isfile(WEIGHTS_PATH):
        _log_missing_weights()
        return False
    return _get_extractor() is not None


def cosine_similarity(a, b) -> float:
    va = np.asarray(a, dtype=np.float32).reshape(-1)
    vb = np.asarray(b, dtype=np.float32).reshape(-1)
    na = float(np.linalg.norm(va))
    nb = float(np.linalg.norm(vb))
    if na <= 0.0 or nb <= 0.0:
        return 0.0
    return float(np.dot(va, vb) / (na * nb))


def extract(crops: list) -> list[list[float]]:
    if not crops:
        return []

    if not os.path.isfile(WEIGHTS_PATH):
        _log_missing_weights()
        return []

    extractor = _get_extractor()
    if extractor is None:
        return []

    try:
        rgb_images = []
        for crop in crops:
            if crop is None or crop.size == 0:
                continue
            rgb = crop[:, :, ::-1].copy()
            rgb_images.append(rgb)
        if not rgb_images:
            return []

        features = extractor(rgb_images)
        if hasattr(features, "detach"):
            features = features.detach().cpu().numpy()
        features = np.asarray(features, dtype=np.float32)
        return [row.tolist() for row in features]
    except Exception as exc:
        _log_extract_failed(exc)
        return []
