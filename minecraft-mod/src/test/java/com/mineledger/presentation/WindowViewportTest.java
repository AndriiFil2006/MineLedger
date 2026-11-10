package com.mineledger.presentation;

import static org.junit.Assert.*;
import org.junit.Test;

public class WindowViewportTest {
    @Test
    public void contentFitsAndStaysCenteredAcrossGuiScalesAndLongFeedback() {
        for (int[] dimensions : new int[][] {{1920, 1080}, {640, 360}, {427, 240}, {320, 240}, {100, 80}}) {
            for (int contentHeight : new int[] {220, 340, 800}) {
                WindowViewport view = WindowViewport.fit(dimensions[0], dimensions[1], 600, contentHeight);
                assertTrue(view.scale() > 0 && view.scale() <= 0.85f);
                assertTrue(view.x() >= 11.99f);
                assertTrue(view.y() >= 11.99f);
                assertTrue(view.screenX(600) <= dimensions[0] - 11.99);
                assertTrue(view.screenY(contentHeight) <= dimensions[1] - 11.99);
                assertEquals(dimensions[0] / 2.0, view.screenX(300), 0.001);
                assertEquals(dimensions[1] / 2.0, view.screenY(contentHeight / 2.0), 0.001);
            }
        }
    }

    @Test
    public void mouseAndTooltipCoordinatesRoundTripIncludingOutsideTheWindow() {
        for (int width : new int[] {320, 427, 640}) {
            WindowViewport view = WindowViewport.fit(width, 240, 600, 340);
            for (double x : new double[] {-10, 0, 14, 300, 586, 600, 610}) {
                assertEquals(x, view.localX(view.screenX(x)), 0.0001);
            }
            for (double y : new double[] {-5, 0, 20, 170, 320, 340, 350}) {
                assertEquals(y, view.localY(view.screenY(y)), 0.0001);
            }
            assertTrue(view.localX(view.x() - 1) < 0);
            assertTrue(view.localY(view.y() - 1) < 0);
        }
    }

    @Test
    public void emptyDimensionsAreRejectedInsteadOfDividingByZero() {
        assertThrows(IllegalArgumentException.class, () -> WindowViewport.fit(0, 240, 600, 340));
        assertThrows(IllegalArgumentException.class, () -> WindowViewport.fit(320, 0, 600, 340));
        assertThrows(IllegalArgumentException.class, () -> WindowViewport.fit(320, 240, 0, 340));
        assertThrows(IllegalArgumentException.class, () -> WindowViewport.fit(320, 240, 600, 0));
    }
}
