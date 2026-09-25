from services.analyzer.utils import _iou

OVERLAP_IOU = 0.30


class OcclusionGuard:
    """Keep each id on the path it had before two player boxes overlapped."""

    def __init__(self):
        self.motion = {}
        self.remap = {}
        self.lock = None

    def apply(self, parsed: list, frame_index: int, player_class_id: int) -> None:
        players = [p for p in parsed if p["classId"] == player_class_id and p["trackId"] >= 0]
        for player in players:
            raw_id = player["trackId"]
            player["_rawTrackId"] = raw_id
            player["trackId"] = self.remap.get(raw_id, raw_id)

        groups = self._overlap_groups(players)
        if groups:
            if self.lock is None:
                ids = []
                for group in groups:
                    for player in group:
                        if player["trackId"] not in ids:
                            ids.append(player["trackId"])
                if len(ids) >= 2:
                    self.lock = {"ids": ids}
            occluded = set(self.lock["ids"]) if self.lock else set()
            for group in groups:
                for player in group:
                    player["occluded"] = True
                    occluded.add(player["trackId"])
            self._update_motion(
                [player for player in players if player["trackId"] not in occluded],
                frame_index,
            )
            return

        if self.lock is not None:
            self._release(players, frame_index)
            self.lock = None
        self._update_motion(players, frame_index)

    def _release(self, players: list, frame_index: int) -> None:
        locked_ids = [track_id for track_id in self.lock["ids"] if track_id in self.motion]
        if len(locked_ids) < 2:
            return
        candidates = [
            player for player in players
            if player["trackId"] in locked_ids or player["_rawTrackId"] in locked_ids
        ]
        if len(candidates) < 2:
            candidates = list(players)
        if len(candidates) < 2:
            return

        choices = []
        for track_id in locked_ids:
            saved = self.motion[track_id]
            dt = max(1, frame_index - saved["frame"])
            predicted_x = saved["cx"] + saved["vx"] * dt
            predicted_y = saved["cy"] + saved["vy"] * dt
            for index, player in enumerate(candidates):
                cx, cy = self._center(player)
                distance = (cx - predicted_x) ** 2 + (cy - predicted_y) ** 2
                choices.append((distance, track_id, index))
        choices.sort()

        used_ids = set()
        used_boxes = set()
        for _distance, track_id, index in choices:
            if track_id in used_ids or index in used_boxes:
                continue
            used_ids.add(track_id)
            used_boxes.add(index)
            player = candidates[index]
            self.remap[player["_rawTrackId"]] = track_id
            player["trackId"] = track_id

    def _update_motion(self, players: list, frame_index: int) -> None:
        for player in players:
            track_id = player["trackId"]
            cx, cy = self._center(player)
            previous = self.motion.get(track_id)
            if previous is None:
                vx, vy = 0.0, 0.0
            else:
                dt = frame_index - previous["frame"]
                if dt > 0:
                    vx = (cx - previous["cx"]) / dt
                    vy = (cy - previous["cy"]) / dt
                else:
                    vx, vy = previous["vx"], previous["vy"]
            self.motion[track_id] = {
                "cx": cx,
                "cy": cy,
                "vx": vx,
                "vy": vy,
                "frame": frame_index,
            }

    def _overlap_groups(self, players: list) -> list:
        count = len(players)
        parent = list(range(count))

        def find(index: int) -> int:
            while parent[index] != index:
                parent[index] = parent[parent[index]]
                index = parent[index]
            return index

        for i in range(count):
            for j in range(i + 1, count):
                if self._box_iou(players[i], players[j]) >= OVERLAP_IOU:
                    parent[find(i)] = find(j)

        groups = {}
        for index, player in enumerate(players):
            groups.setdefault(find(index), []).append(player)
        return [group for group in groups.values() if len(group) >= 2]

    @staticmethod
    def _center(player: dict) -> tuple[float, float]:
        return (player["x1"] + player["x2"]) / 2.0, (player["y1"] + player["y2"]) / 2.0

    @staticmethod
    def _box_iou(a: dict, b: dict) -> float:
        return _iou((a["x1"], a["y1"], a["x2"], a["y2"]), (b["x1"], b["y1"], b["x2"], b["y2"]))
