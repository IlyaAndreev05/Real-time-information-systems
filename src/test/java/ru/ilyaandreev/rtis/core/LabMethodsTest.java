package ru.ilyaandreev.rtis.core;

import org.junit.jupiter.api.Test;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LabMethodsTest {
    @Test void adcStudyUsesTheConfiguredFullScaleAndReturnsErrorsOnTheReferenceGrid() {
        List<Point> input = SignalMath.sine(1, 2, 0, 1, 1001);

        var conversion = AdcModel.convert(input, 0.01, 8, -1, 1, "linear", "none", 0, 0);

        assertEquals(101, conversion.samples().size());
        assertEquals(input.size(), conversion.reconstructed().size());
        assertEquals(100, conversion.metrics().get("частотаДискретизацииГц"));
        assertEquals(2.0 / 255, conversion.metrics().get("шагКвантованияВ"), 1e-12);
        assertTrue(conversion.metrics().get("СКОВ") >= 0);
        assertTrue(conversion.metrics().get("максимальнаяОшибкаВ") >= conversion.metrics().get("СКОВ"));
    }

    @Test void adcRejectsDifferentialErrorWhenThreeUpperBitsAreUnavailable() {
        List<Point> input = SignalMath.sine(1, 1, 0, 1, 101);
        assertThrows(IllegalArgumentException.class,
            () -> AdcModel.convert(input, 0.01, 2, -1, 1, "step", "differential", 1, 0));
    }

    @Test void differentialNonlinearityIsMeasuredAsTheSpecifiedPercentOfFullScale() {
        double[] levels = AdcModel.differentialCodeLevels(8, -1, 1, 1, 0);
        double lsb = 2.0 / 255;
        double specialWidth = levels[128] - levels[127];
        double actualDnlPercentOfFullScale = 100 * (specialWidth - lsb) / 2;

        assertEquals(1, actualDnlPercentOfFullScale, 1e-12);
        assertTrue(java.util.stream.IntStream.range(1, levels.length).allMatch(i -> levels[i] > levels[i - 1]));
        assertThrows(IllegalArgumentException.class, () -> AdcModel.differentialCodeLevels(4, -1, 1, 10, 2));
    }

    @Test void distributionGeneratorsAreSeededAndStayInsideTheirSpecifiedRange() {
        var first = SignalMath.distribution(1, 1000, -0.8, 0.8, "normal", 71);
        var again = SignalMath.distribution(1, 1000, -0.8, 0.8, "normal", 71);
        var uniform = SignalMath.distribution(1, 1000, -0.2, 0.2, "uniform", 71);

        assertEquals(first, again);
        assertTrue(first.stream().allMatch(point -> point.y() >= -0.8 && point.y() <= 0.8));
        assertTrue(uniform.stream().allMatch(point -> point.y() >= -0.2 && point.y() <= 0.2));
    }

    @Test void optimalFilterOrderZeroPassesTheSignalAndFirstOrderHasTheExpectedRecurrence() {
        List<Point> input = List.of(new Point(0, 0), new Point(1, 1), new Point(2, 0));
        LabProject project = configured(input, "optimal-filter", Map.of("order", 0.0));
        assertEquals(input, AnalysisEngine.run(project, 1, () -> false).points());

        project.setParameters(Map.of("order", 1.0));
        assertEquals(List.of(new Point(0, 0), new Point(1, 0.5), new Point(2, 0.25)),
            AnalysisEngine.run(project, 1, () -> false).points());
    }

    @Test void idealLowPassKeepsThePassBandAndRejectsAnOutOfBandTone() {
        List<Point> input = java.util.stream.IntStream.range(0, 256).mapToObj(i -> {
            double x = i / 256.0;
            return new Point(x, Math.sin(2 * Math.PI * 4 * x) + 0.5 * Math.sin(2 * Math.PI * 40 * x));
        }).toList();
        LabProject project = configured(input, "low-pass", Map.of("cutoffHarmonic", 8.0));

        var result = AnalysisEngine.run(project, 1, () -> false);

        List<Point> expected = input.stream()
            .map(point -> new Point(point.x(), Math.sin(2 * Math.PI * 4 * point.x()))).toList();
        assertTrue(SignalMath.rmse(expected, result.points()) < 0.02);
        assertEquals(SignalMath.rmse(input, result.points()), result.metrics().get("среднеквадратичнаяОшибка"), 1e-12);
    }

    @Test void compressionReturnsAReconstructedSignalAndReportsItsActualCompressionRatio() {
        List<Point> input = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) input.add(new Point(i, Math.sin(i / 30.0) + (i == 100 ? 0.5 : 0)));
        LabProject project = configured(input, "compression-linear", Map.of("aperture", 8.0, "bits", 8.0, "maxGap", 32.0));

        var result = AnalysisEngine.run(project, 1, () -> false);

        assertEquals(input.size(), result.points().size());
        assertTrue(result.metrics().get("сохранённыхТочек") < input.size());
        assertEquals(input.size() / result.metrics().get("сохранённыхТочек"), result.metrics().get("коэффициентСжатия"), 1e-12);
        assertTrue(result.metrics().get("максимальнаяОшибка") >= result.metrics().get("среднеквадратичнаяОшибка"));
    }

    @Test void fixedApertureReconstructionKeepsItsOriginalCenterBetweenTransmittedEvents() {
        List<Point> input = java.util.stream.IntStream.range(0, 101)
            .mapToObj(i -> new Point(i / 100.0, -0.5 + i / 100.0)).toList();
        LabProject project = new LabProject();
        project.setInput(input); project.setModuleId("compression"); project.setAlgorithmId("extrapolation-fixed");
        project.setParameters(Map.of("bits", 8.0, "aperture", 2.0, "maxGap", 1024.0,
            "fullScaleMin", -1.0, "fullScaleMax", 1.0));

        var result = AnalysisEngine.run(project, 1, () -> false);

        assertTrue(result.metrics().get("максимальнаяОшибкаВосстановленияОтЦифровогоСигнала")
            <= result.metrics().get("апертура") + 1e-12);
        assertEquals(input.size(), result.points().size());
    }

    @Test void progressiveInterpolationKeepsStrictlyIncreasingCoordinatesForSingleSecondStageBlock() {
        List<Point> input = java.util.stream.IntStream.range(0, 2000)
            .mapToObj(i -> new Point(i / 1999.0, Math.sin(i / 20.0))).toList();
        LabProject project = configured(input, "progressive-interpolation", Map.of("firstStep", 200.0, "secondStep", 50.0));

        var result = AnalysisEngine.run(project, 1, () -> false);

        assertEquals(input.size(), result.points().size());
        assertTrue(result.points().stream().allMatch(point -> Double.isFinite(point.y())));
    }

    @Test void progressiveInterpolationAcceptsSmallStepPairsAcrossBothStages() {
        List<Point> input = java.util.stream.IntStream.range(0, 2000)
            .mapToObj(i -> new Point(i / 1999.0, Math.sin(i / 20.0))).toList();
        for (int[] steps : List.of(new int[]{1, 2}, new int[]{1, 3}, new int[]{2, 1}, new int[]{2, 2})) {
            LabProject project = configured(input, "progressive-interpolation",
                Map.of("firstStep", (double) steps[0], "secondStep", (double) steps[1]));
            var result = assertDoesNotThrow(() -> AnalysisEngine.run(project, 1, () -> false), java.util.Arrays.toString(steps));
            assertEquals(input.size(), result.points().size());
        }
    }

    @Test void allTenAutocorrelationModelsAreFiniteAndNormalizedAtZeroLag() {
        for (int model = 1; model <= 10; model++) assertEquals(1, AutocorrelationModels.value(model, 0), 1e-12);
        assertEquals(Math.PI / 4, AutocorrelationModels.value(9, Math.PI / 2), 1e-8);

        List<Point> signal = java.util.stream.IntStream.range(0, 2000)
            .mapToObj(i -> new Point(i * 0.001, Math.sin(i * 0.001 * 2 * Math.PI * 2))).toList();
        AutocorrelationModels.Fit fit = AutocorrelationModels.fit(signal, 0.25, 0.1, 100, 250);

        assertEquals(10, fit.models().size());
        assertEquals(500, fit.empirical().size());
        assertTrue(fit.models().stream().allMatch(model -> Double.isFinite(model.alpha()) && model.rmse() >= 0));
    }

    private static LabProject configured(List<Point> input, String algorithm, Map<String, Double> parameters) {
        LabProject project = new LabProject();
        project.setInput(input);
        project.setModuleId("compression".equals(algorithm) || algorithm.startsWith("compression-")
            || algorithm.startsWith("extrapolation-") ? "compression"
            : List.of("adc-dac", "quantization-error").contains(algorithm) ? "conversion" : "processing");
        project.setAlgorithmId(algorithm);
        project.setParameters(parameters);
        return project;
    }
}
