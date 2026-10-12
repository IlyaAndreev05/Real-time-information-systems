package ru.ilyaandreev.rtis.core;

import org.junit.jupiter.api.Test;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;

import java.util.List;
import java.util.Map;
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

    @Test void manualFivePointInterpolationKeepsEveryKnotAndBuildsDenseCurve() {
        List<Point> knots = List.of(new Point(0, 0), new Point(0.25, 1), new Point(0.5, 0),
            new Point(0.75, -1), new Point(1, 0));
        LabProject project = new LabProject();
        project.setInput(knots);
        project.setInputKind("controlPoints");
        project.setModuleId("processing");
        project.setAlgorithmId("linear-interpolation");
        project.setParameters(Map.of("stride", 4.0)); // Manual knots must not be decimated by this sample stride.

        var result = AnalysisEngine.run(project, 1, () -> false);

        assertEquals(430, result.points().size()); // The original SRV line contains 430 display samples.
        assertEquals(5.0, result.metrics().get("узловыхТочек"));
        assertEquals(430.0, result.metrics().get("точекВосстановления"));
        assertEquals(0.0, result.metrics().get("среднеквадратичнаяОшибкаНаУзлах"), 1e-12);
        for (Point knot : knots) assertTrue(result.points().contains(knot), "missing exact knot " + knot);
        assertEquals(0.5, SignalMath.interpolateAt(result.points(), 0.125).y(), 1e-12);
        assertEquals(0.5, SignalMath.interpolateAt(result.points(), 0.375).y(), 1e-12);
        assertEquals(-0.5, SignalMath.interpolateAt(result.points(), 0.625).y(), 1e-12);
    }

    @Test void sampledInterpolationStillUsesStrideAndOriginalSampleCoordinates() {
        LabProject project = new LabProject();
        project.setInput(SignalMath.sine(1, 1, 0, 1, 9));
        project.setModuleId("processing");
        project.setAlgorithmId("linear-interpolation");
        project.setParameters(Map.of("stride", 4.0));

        var result = AnalysisEngine.run(project, 1, () -> false);

        assertEquals(9, result.points().size());
        assertEquals(3.0, result.metrics().get("узловыхТочек"));
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

    @Test void zeroCrossingsAreLinearlyInterpolatedAndExactZerosAreUnique() {
        List<Point> signal = List.of(new Point(0, -1), new Point(2, 1), new Point(3, 0), new Point(4, -2));
        assertEquals(List.of(new Point(1, 0), new Point(3, 0)), SignalMath.zeroCrossings(signal));
    }

    @Test void measurementCursorUsesLinearInterpolation() {
        List<Point> signal = List.of(new Point(1, 2), new Point(3, 6));
        assertEquals(new Point(2, 4), SignalMath.interpolateAt(signal, 2));
        assertThrows(IllegalArgumentException.class, () -> SignalMath.interpolateAt(signal, 4));
    }

    @Test void algorithmCatalogExposesNamedBoundedParameters() {
        var stride = AlgorithmCatalog.ALGORITHMS.get("linear-interpolation").parameters().getFirst();
        assertEquals("Шаг дискретизации", stride.name());
        assertEquals(1, stride.min());
        assertEquals(1, stride.step());
        assertTrue(stride.integer());
    }
}
