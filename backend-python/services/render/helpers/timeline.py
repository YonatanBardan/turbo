# Groups payload items by their frameIndex so the render loop can look them up per frame
def group_by_frame(items: list) -> dict[int, list]:
    by_frame: dict[int, list] = {}
    for item in items or []:
        by_frame.setdefault(int(item.frameIndex), []).append(item)
    return by_frame


# Maps each frameIndex to the ball state label that starts on that frame
def labels_by_frame(states: list) -> dict[int, str]:
    return {int(state.frameIndex): state.label for state in states or []}
