package ru.ilyaandreev.rtis.core;

import org.junit.jupiter.api.Test;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisEngineTest {
    @Test void fourierFindsAUnitAmplitudeHarmonic() {
        LabProject project = new LabProject();
        project.setInput(SignalMath.sine(1, 8, 0, 511.0 / 512, 512));
        project.setAlgorithmId("fourier");
        var result = AnalysisEngine.run(project, 0, () -> false);
        Point peak = result.points().stream().max(java.util.Comparator.comparingDouble(Point::y)).orElseThrow();
        assertEquals(8.0, peak.x(), 1e-10);
        assertEquals(1.0, peak.y(), 1e-10);
    }

    @Test void linearInterpolationReproducesALine() {
        List<Point> knots = List.of(new Point(0, 2), new Point(1, 4), new Point(2, 6));
        assertEquals(List.of(new Point(0, 2), new Point(0.5, 3), new Point(1, 4), new Point(2, 6)),
            SignalMath.linearInterpolate(knots, List.of(0.0, 0.5, 1.0, 2.0)));
    }

    @Test void projectRejectsNonIncreasingTimeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> SignalMath.requireSignal(List.of(new Point(0, 1), new Point(0, 2))));
    }

    @Test void cancelledCalculationStopsBeforePublishingResult() {
        LabProject project = new LabProject();
        project.setInput(SignalMath.sine(1, 4, 0, 1, 256));
        project.setAlgorithmId("fourier");
        assertThrows(CancellationException.class, () -> AnalysisEngine.run(project, 1, () -> true));
    }

    @Test void quantizationHonorsBitLimitAndRange() {
        List<Point> quantized = SignalMath.quantize(List.of(new Point(0, -4), new Point(1, 4)), 3, -1, 1);
        assertEquals(-1, quantized.getFirst().y());
        assertEquals(1, quantized.getLast().y());
        assertThrows(IllegalArgumentException.class, () -> SignalMath.quantize(quantized, 25, -1, 1));
    }
}
