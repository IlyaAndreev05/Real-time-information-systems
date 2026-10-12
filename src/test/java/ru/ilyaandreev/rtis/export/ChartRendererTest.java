package ru.ilyaandreev.rtis.export;

import org.junit.jupiter.api.Test;
import ru.ilyaandreev.rtis.model.LabProject;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChartRendererTest {
    @Test void emptyProjectStartsWithAStableManualDrawingRange() {
        var axes = ChartRenderer.createChart(new LabProject()).getXYPlot();

        assertEquals(0, axes.getDomainAxis().getLowerBound(), 0);
        assertEquals(1, axes.getDomainAxis().getUpperBound(), 0);
        assertEquals(-1, axes.getRangeAxis().getLowerBound(), 0);
        assertEquals(1, axes.getRangeAxis().getUpperBound(), 0);
    }
}
