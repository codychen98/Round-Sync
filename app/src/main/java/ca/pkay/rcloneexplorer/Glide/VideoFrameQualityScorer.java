package ca.pkay.rcloneexplorer.Glide;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

final class VideoFrameQualityScorer {
    private static final int SAMPLE_GRID = 6;
    private static final double FLAT_FRAME_CONTRAST_MAX = 18d;
    private static final double FLAT_FRAME_DETAIL_MAX = 16d;
    private static final double DARK_FRAME_BRIGHTNESS_MIN = 12d;
    private static final double REPRESENTATIVE_SCORE_MIN = 18d;

    private VideoFrameQualityScorer() {
    }

    @NonNull
    static FrameQuality score(@NonNull Bitmap bitmap) {
        int width = Math.max(1, bitmap.getWidth());
        int height = Math.max(1, bitmap.getHeight());
        double[] previousRowLuma = new double[SAMPLE_GRID];
        double totalLuma = 0d;
        double totalLumaSquared = 0d;
        double totalSaturation = 0d;
        double totalDetail = 0d;
        int samples = 0;

        for (int gy = 0; gy < SAMPLE_GRID; gy++) {
            double leftLuma = -1d;
            for (int gx = 0; gx < SAMPLE_GRID; gx++) {
                int px = sampleCoord(gx, SAMPLE_GRID, width);
                int py = sampleCoord(gy, SAMPLE_GRID, height);
                int pixel = bitmap.getPixel(px, py);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                double luma = (0.299d * r) + (0.587d * g) + (0.114d * b);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));

                totalLuma += luma;
                totalLumaSquared += luma * luma;
                totalSaturation += (max - min);
                if (leftLuma >= 0d) {
                    totalDetail += Math.abs(luma - leftLuma);
                }
                if (gy > 0) {
                    totalDetail += Math.abs(luma - previousRowLuma[gx]);
                }
                previousRowLuma[gx] = luma;
                leftLuma = luma;
                samples++;
            }
        }

        double avgLuma = totalLuma / samples;
        double lumaVariance = Math.max(0d, (totalLumaSquared / samples) - (avgLuma * avgLuma));
        double lumaStdDev = Math.sqrt(lumaVariance);
        double avgSaturation = totalSaturation / samples;
        double detailSamples = (SAMPLE_GRID - 1d) * SAMPLE_GRID * 2d;
        double detailEstimate = detailSamples > 0d ? totalDetail / detailSamples : 0d;
        boolean flatColorLike = lumaStdDev < FLAT_FRAME_CONTRAST_MAX && detailEstimate < FLAT_FRAME_DETAIL_MAX;
        double informativeScore = (Math.min(avgLuma, 80d) * 0.15d)
                + (lumaStdDev * 0.35d)
                + (detailEstimate * 0.35d)
                + (avgSaturation * 0.15d)
                - (flatColorLike ? 18d : 0d)
                - (avgLuma < DARK_FRAME_BRIGHTNESS_MIN ? 10d : 0d);
        boolean nearMonochrome = avgSaturation < 14d && lumaStdDev < 18d && detailEstimate < 16d;
        boolean poorRepresentativeCandidate = informativeScore < REPRESENTATIVE_SCORE_MIN;
        return new FrameQuality(
                avgLuma,
                lumaStdDev,
                avgSaturation,
                detailEstimate,
                informativeScore,
                nearMonochrome,
                flatColorLike,
                poorRepresentativeCandidate);
    }

    private static int sampleCoord(int gridIndex, int gridSize, int bound) {
        if (bound <= 1 || gridSize <= 1) {
            return 0;
        }
        return Math.min(bound - 1, (gridIndex * (bound - 1)) / (gridSize - 1));
    }

    static final class FrameQuality {
        private final double brightness;
        private final double contrast;
        private final double colorVariance;
        private final double detail;
        private final double informativeScore;
        private final boolean nearMonochrome;
        private final boolean flatColorLike;
        private final boolean poorRepresentativeCandidate;

        private FrameQuality(
                double brightness,
                double contrast,
                double colorVariance,
                double detail,
                double informativeScore,
                boolean nearMonochrome,
                boolean flatColorLike,
                boolean poorRepresentativeCandidate) {
            this.brightness = brightness;
            this.contrast = contrast;
            this.colorVariance = colorVariance;
            this.detail = detail;
            this.informativeScore = informativeScore;
            this.nearMonochrome = nearMonochrome;
            this.flatColorLike = flatColorLike;
            this.poorRepresentativeCandidate = poorRepresentativeCandidate;
        }

        double brightness() {
            return brightness;
        }

        double informativeScore() {
            return informativeScore;
        }

        boolean isNearMonochrome() {
            return nearMonochrome;
        }

        boolean isFlatColorLike() {
            return flatColorLike;
        }

        boolean isPoorRepresentativeCandidate() {
            return poorRepresentativeCandidate;
        }
    }
}
