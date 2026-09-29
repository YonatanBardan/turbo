package com.turbo.backend_analytics.dto.Shot;

import com.turbo.backend_analytics.dto.DetectionBox;

public record Shot(
        DetectionBox shooter,
        DetectionBox groundBox,   // shooter's last box with feet on the floor before the jump
        double makeProbability,
        String time,
        int frameIndex,
        boolean isMake,
        int shooterTrackId,
        int passerTrackId,
        boolean assist,
        double mapped_x,
        double mapped_y
) {
    public Shot(
            DetectionBox shooter,
            double makeProbability,
            String time,
            int frameIndex,
            boolean isMake
    ) {
        this(
                shooter,
                shooter,
                makeProbability,
                time,
                frameIndex,
                isMake,
                shooter != null ? shooter.trackId() : -1,
                -1,
                false,
                0.0,
                0.0
        );
    }

    public Shot withCredit(int shooterId, int passerId, boolean creditedAssist) {
        return new Shot(
                shooter,
                groundBox,
                makeProbability,
                time,
                frameIndex,
                isMake,
                shooterId,
                passerId,
                creditedAssist,
                mapped_x,
                mapped_y
        );
    }

    // Returns a copy that maps the shot from the given take-off box instead of the release box
    public Shot withGroundBox(DetectionBox takeOffBox) {
        return new Shot(
                shooter,
                takeOffBox != null ? takeOffBox : shooter,
                makeProbability,
                time,
                frameIndex,
                isMake,
                shooterTrackId,
                passerTrackId,
                assist,
                mapped_x,
                mapped_y
        );
    }
}
