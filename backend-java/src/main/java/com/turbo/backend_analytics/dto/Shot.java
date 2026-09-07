package com.turbo.backend_analytics.dto;

public record Shot(
        DetectionBox shooter,
        double makeProbability,
        String time,
        int frameIndex,
        boolean isMake,
        int shooterTrackId,
        int passerTrackId,
        boolean assist
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
                makeProbability,
                time,
                frameIndex,
                isMake,
                shooter != null ? shooter.trackId() : -1,
                -1,
                false
        );
    }

    public Shot withCredit(int shooterId, int passerId, boolean creditedAssist) {
        return new Shot(
                shooter,
                makeProbability,
                time,
                frameIndex,
                isMake,
                shooterId,
                passerId,
                creditedAssist
        );
    }
}
