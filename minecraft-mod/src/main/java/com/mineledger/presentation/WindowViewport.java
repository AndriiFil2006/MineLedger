package com.mineledger.presentation;

/** Pure coordinate math shared by rendering, mouse input and deferred tooltips. */
public record WindowViewport(float scale, float x, float y) {
    public static WindowViewport fit(int width, int height, int contentWidth, int contentHeight) {
        if (width <= 0 || height <= 0 || contentWidth <= 0 || contentHeight <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }
        float scale = Math.min(0.85f, Math.min(Math.max(1, width - 24) / (float) contentWidth,
                Math.max(1, height - 24) / (float) contentHeight));
        return new WindowViewport(scale, (width - contentWidth * scale) / 2, (height - contentHeight * scale) / 2);
    }

    public double localX(double screenX) { return (screenX - x) / scale; }
    public double localY(double screenY) { return (screenY - y) / scale; }
    public double screenX(double localX) { return x + localX * scale; }
    public double screenY(double localY) { return y + localY * scale; }
}
