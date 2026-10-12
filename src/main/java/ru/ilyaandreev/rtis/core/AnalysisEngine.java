package ru.ilyaandreev.rtis.core;

import ru.ilyaandreev.rtis.model.ExperimentResult;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Numeric calculations used by the GUI and MCP interfaces. */
public final class AnalysisEngine {
    /** SRV's graph was 430 horizontal pixels wide; use the same dense visual sampling. */
    private static final int MANUAL_INTERPOLATION_SAMPLES = 430;

    private AnalysisEngine() { }

    /** Return transmitted quantized knots for a compression project without changing its saved state. */
    public static List<Point> compressionKnots(LabProject project) {
        String algorithm = project.getAlgorithmId();
        if (!List.of("compression-zero-order", "compression-linear", "extrapolation-fixed", "extrapolation-floating", "extrapolation-linear").contains(algorithm))
            throw new IllegalArgumentException("Текущий проект не использует алгоритм сжатия");
        List<Point> source = project.getInput();
        SignalMath.requireSignal(source);
        Map<String, Double> p = project.getParameters();
        int bits = integer(p, "bits", 8);
        double[] signalRange = range(source);
        double min = p.getOrDefault("fullScaleMin", signalRange[0]);
        double max = p.getOrDefault("fullScaleMax", signalRange[1]);
        if (!(max > min)) throw new IllegalArgumentException("Шкала АЦП должна задавать положительный диапазон");
        double aperture = p.getOrDefault("aperture", 1.0) * (max - min) / ((1L << bits) - 1);
        return compress(SignalMath.quantize(source, bits, min, max), aperture,
            integer(p, "maxGap", 32), algorithm, () -> false).knots();
    }

    public static ExperimentResult run(LabProject project, long inputRevision, BooleanSupplier cancelled) {
        List<Point> source = project.getInput();
        SignalMath.requireSignal(source);
        Map<String, Double> p = project.getParameters();
        List<Point> output;
        Map<String, Double> metrics = new LinkedHashMap<>();
        String algorithm = project.getAlgorithmId();
        switch (algorithm) {
            case "fourier" -> output = fourier(source, cancelled);
            case "autocorrelation" -> output = autocorrelation(source, cancelled);
            case "sample-hold", "linear-reconstruction" -> {
                List<Point> knots = interpolationKnots(project, source, p);
                List<Double> xs = interpolationXValues(project, source);
                output = algorithm.equals("sample-hold") ? SignalMath.stepInterpolate(knots, xs) : SignalMath.linearInterpolate(knots, xs);
                addInterpolationMetrics(project, knots, output, metrics);
            }
            case "quantize" -> {
                int bits = integer(p, "bits", 8);
                double[] range = range(source);
                double min = p.getOrDefault("fullScaleMin", range[0]), max = p.getOrDefault("fullScaleMax", range[1]);
                output = SignalMath.quantize(source, bits, min, max);
                metrics.put("бит", (double) bits);
                metrics.put("шагКвантования", (max - min) / ((1L << bits) - 1));
                metrics.put("теоретическаяМаксПогрешность", (max - min) / (2 * ((1L << bits) - 1)));
            }
            case "adc-dac", "quantization-error" -> {
                String[] errors = {"none", "additive", "multiplicative", "integral", "differential", "combined"};
                int errorMode = integer(p, "errorMode", 0);
                if (errorMode < 0 || errorMode >= errors.length) throw new IllegalArgumentException("errorMode должен быть от 0 до 5");
                int bits = integer(p, "bits", 8);
                double[] signalRange = range(source);
                var conversion = AdcModel.convert(source, p.getOrDefault("sampleInterval", medianInterval(source)), bits,
                    p.getOrDefault("fullScaleMin", signalRange[0]), p.getOrDefault("fullScaleMax", signalRange[1]),
                    integer(p, "reconstructionMode", 0) == 1 ? "linear" : "step", errors[errorMode],
                    p.getOrDefault("errorPercent", 0.0), integer(p, "differentialBit", 0));
                output = conversion.reconstructed();
                metrics.putAll(conversion.metrics());
                metrics.put("типИнструментальнойОшибки", (double) errorMode);
            }
            case "step-interpolation", "linear-interpolation", "cubic-spline", "progressive-interpolation" -> {
                List<Point> knots = interpolationKnots(project, source, p);
                List<Double> xs = interpolationXValues(project, source);
                output = switch (algorithm) {
                    case "step-interpolation" -> SignalMath.stepInterpolate(knots, xs);
                    case "linear-interpolation" -> SignalMath.linearInterpolate(knots, xs);
                    case "progressive-interpolation" -> progressiveInterpolate(source,
                        integer(p, "firstStep", 10), integer(p, "secondStep", 5), cancelled);
                    default -> SignalMath.cubicSpline(knots, xs);
                };
                addInterpolationMetrics(project, knots, output, metrics);
            }
            case "add-noise" -> output = SignalMath.addGaussianNoise(source, p.getOrDefault("standardDeviation", 0.1), project.getNoiseSeed());
            case "moving-average" -> output = movingAverage(source, integer(p, "window", 5),
                integer(p, "sampleIndex", 0), integer(p, "useFilteredValues", 0) == 1, cancelled);
            case "first-order-filter" -> output = firstOrderFilter(source, p.getOrDefault("alpha", 0.25), cancelled);
            case "optimal-filter" -> output = optimalFilter(source, integer(p, "order", 4), cancelled);
            case "low-pass" -> output = lowPass(source, integer(p, "cutoffHarmonic", 10), cancelled);
            case "compression-zero-order", "compression-linear", "extrapolation-fixed", "extrapolation-floating", "extrapolation-linear" -> {
                int bits = integer(p, "bits", 8);
                double[] signalRange = range(source);
                double fullScaleMin = p.getOrDefault("fullScaleMin", signalRange[0]);
                double fullScaleMax = p.getOrDefault("fullScaleMax", signalRange[1]);
                if (!(fullScaleMax > fullScaleMin)) throw new IllegalArgumentException("Шкала АЦП должна задавать положительный диапазон");
                double fullScale = fullScaleMax - fullScaleMin;
                double aperture = p.getOrDefault("aperture", 1.0) * fullScale / ((1L << bits) - 1);
                List<Point> digitized = SignalMath.quantize(source, bits, fullScaleMin, fullScaleMax);
                Compression compression = compress(digitized, aperture, integer(p, "maxGap", 32), algorithm, cancelled);
                output = compression.reconstructed();
                metrics.put("коэффициентСжатия", source.size() / (double) compression.knots().size());
                metrics.put("исходныхТочек", (double) source.size());
                metrics.put("сохранённыхТочек", (double) compression.knots().size());
                metrics.put("апертура", aperture);
                metrics.put("квантовАпертуры", p.getOrDefault("aperture", 1.0));
                metrics.put("среднеквадратичнаяОшибка", rmse(source, compression.reconstructed()));
                metrics.put("максимальнаяОшибка", maxError(source, compression.reconstructed()));
                metrics.put("ошибкаКвантованияСКО", rmse(source, digitized));
                metrics.put("ошибкаКвантованияМакс", maxError(source, digitized));
                metrics.put("СКОВосстановленияОтЦифровогоСигнала", rmse(digitized, compression.reconstructed()));
                metrics.put("максимальнаяОшибкаВосстановленияОтЦифровогоСигнала", maxError(digitized, compression.reconstructed()));
            }
            default -> throw new IllegalArgumentException("Алгоритм пока не реализован: " + algorithm);
        }
        if (output.size() == source.size()) {
            metrics.putIfAbsent("среднеквадратичнаяОшибка", rmse(source, output));
            metrics.putIfAbsent("максимальнаяОшибка", maxError(source, output));
        }
        checkCancelled(cancelled);
        return new ExperimentResult(UUID.randomUUID().toString(), project.getModuleId(), algorithm, inputRevision, output, metrics);
    }

    private static List<Point> interpolationKnots(LabProject project, List<Point> source, Map<String, Double> parameters) {
        return "controlPoints".equals(project.getInputKind())
            ? source
            : SignalMath.sample(source, integer(parameters, "stride", 4));
    }

    private static List<Double> interpolationXValues(LabProject project, List<Point> source) {
        return "controlPoints".equals(project.getInputKind())
            ? SignalMath.interpolationGrid(source, MANUAL_INTERPOLATION_SAMPLES)
            : xValues(source);
    }

    private static void addInterpolationMetrics(LabProject project, List<Point> knots, List<Point> output,
                                                Map<String, Double> metrics) {
        metrics.put("узловыхТочек", (double) knots.size());
        metrics.put("точекВосстановления", (double) output.size());
        if ("controlPoints".equals(project.getInputKind())) {
            double sum = 0, max = 0;
            for (Point knot : knots) {
                double error = Math.abs(knot.y() - SignalMath.interpolateAt(output, knot.x()).y());
                sum += error * error;
                max = Math.max(max, error);
            }
            metrics.put("среднеквадратичнаяОшибкаНаУзлах", Math.sqrt(sum / knots.size()));
            metrics.put("максимальнаяОшибкаНаУзлах", max);
        }
    }

    private static List<Point> fourier(List<Point> input, BooleanSupplier cancelled) {
        int n = input.size();
        double dt = (input.getLast().x() - input.getFirst().x()) / (n - 1);
        int size = 1;
        while (size < n) size <<= 1;
        double[] re = new double[size], im = new double[size];
        for (int i = 0; i < n; i++) re[i] = input.get(i).y();
        for (int i = 1, j = 0; i < size; i++) {
            int bit = size >>> 1;
            for (; (j & bit) != 0; bit >>>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double r = re[i]; re[i] = re[j]; re[j] = r;
                double v = im[i]; im[i] = im[j]; im[j] = v;
            }
        }
        for (int length = 2; length <= size; length <<= 1) {
            checkCancelled(cancelled);
            double angle = -2 * Math.PI / length;
            double stepRe = Math.cos(angle), stepIm = Math.sin(angle);
            for (int start = 0; start < size; start += length) {
                double wRe = 1, wIm = 0;
                for (int j = 0; j < length / 2; j++) {
                    int even = start + j, odd = even + length / 2;
                    double oddRe = re[odd] * wRe - im[odd] * wIm;
                    double oddIm = re[odd] * wIm + im[odd] * wRe;
                    re[odd] = re[even] - oddRe; im[odd] = im[even] - oddIm;
                    re[even] += oddRe; im[even] += oddIm;
                    double next = wRe * stepRe - wIm * stepIm;
                    wIm = wRe * stepIm + wIm * stepRe; wRe = next;
                }
            }
        }
        List<Point> out = new ArrayList<>(size / 2 + 1);
        for (int k = 0; k <= size / 2; k++) {
            double amplitude = Math.hypot(re[k], im[k]) / n;
            if (k > 0 && !(size % 2 == 0 && k == size / 2)) amplitude *= 2;
            out.add(new Point(k / (size * dt), amplitude));
        }
        return out;
    }

    private static List<Point> autocorrelation(List<Point> input, BooleanSupplier cancelled) {
        int n = input.size();
        double mean = input.stream().mapToDouble(Point::y).average().orElse(0);
        List<Point> out = new ArrayList<>(n);
        for (int lag = 0; lag < n; lag++) {
            checkCancelled(cancelled);
            double sum = 0;
            for (int i = 0; i < n - lag; i++) sum += (input.get(i).y() - mean) * (input.get(i + lag).y() - mean);
            out.add(new Point((input.get(lag).x() - input.getFirst().x()), sum / (n - lag)));
        }
        return out;
    }

    private static List<Point> movingAverage(List<Point> input, int window, BooleanSupplier cancelled) {
        return movingAverage(input, window, 0, false, cancelled);
    }

    private static List<Point> movingAverage(List<Point> input, int window, int sampleIndex,
                                             boolean useFilteredValues, BooleanSupplier cancelled) {
        if (window < 1 || window > input.size()) throw new IllegalArgumentException("Окно фильтра должно быть от 1 до числа точек");
        if (sampleIndex < 0 || sampleIndex >= window) throw new IllegalArgumentException("Номер выборки должен быть от 0 до N−1");
        double[] filtered = new double[input.size()];
        for (int i = 0; i < input.size(); i++) {
            checkCancelled(cancelled);
            double sum = 0;
            for (int j = Math.max(0, i - window + 1); j <= i; j++)
                sum += useFilteredValues && j < i ? filtered[j] : input.get(j).y();
            filtered[i] = sum / Math.min(window, i + 1);
        }
        List<Point> out = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            // The PDF names j but does not define it. The Java contract binds each mean to
            // sample k-j inside its causal N-sample window; j changes the output timestamp only.
            int sourceIndex = Math.min(input.size() - 1, i + window - 1 - sampleIndex);
            out.add(new Point(input.get(i).x(), filtered[sourceIndex]));
        }
        return out;
    }

    private static List<Point> firstOrderFilter(List<Point> input, double alpha, BooleanSupplier cancelled) {
        if (!(alpha > 0 && alpha <= 1)) throw new IllegalArgumentException("Коэффициент фильтра alpha должен быть в (0; 1]");
        List<Point> out = new ArrayList<>(input.size());
        double y = input.getFirst().y();
        for (Point point : input) {
            checkCancelled(cancelled);
            y += alpha * (point.y() - y);
            out.add(new Point(point.x(), y));
        }
        return out;
    }

    private static List<Point> optimalFilter(List<Point> input, int order, BooleanSupplier cancelled) {
        if (order < 0 || order > 14) throw new IllegalArgumentException("Параметр оптимального фильтра должен быть от 0 до 14");
        double currentWeight = 1.0 / (1L << order);
        List<Point> out = new ArrayList<>(input.size());
        double y = input.getFirst().y();
        for (int i = 0; i < input.size(); i++) {
            checkCancelled(cancelled);
            Point point = input.get(i);
            y += currentWeight * (point.y() - y);
            out.add(new Point(point.x(), y));
        }
        return out;
    }

    private static List<Point> progressiveInterpolate(List<Point> input, int firstStep, int secondStep,
                                                       BooleanSupplier cancelled) {
        if (firstStep < 1 || firstStep > 200 || secondStep < 1 || secondStep > 50)
            throw new IllegalArgumentException("Шаги прогрессивной интерполяции вне методического диапазона");
        List<Point> first = averageBlocks(input, firstStep, cancelled);
        List<Point> second = averageBlocks(first, secondStep, cancelled);
        return SignalMath.linearInterpolate(second, xValues(input));
    }

    private static List<Point> averageBlocks(List<Point> input, int blockSize, BooleanSupplier cancelled) {
        List<Point> out = new ArrayList<>();
        for (int from = 0; from < input.size(); from += blockSize) {
            checkCancelled(cancelled);
            int to = Math.min(input.size(), from + blockSize);
            double x = 0, y = 0;
            for (int i = from; i < to; i++) { x += input.get(i).x(); y += input.get(i).y(); }
            x /= (to - from);
            if (from == 0) x = input.getFirst().x();
            if (to == input.size()) x = input.getLast().x();
            out.add(new Point(x, y / (to - from)));
        }
        // A single full-width block represents a constant average over the complete time range.
        if (out.size() == 1 && input.size() > 1) {
            double average = out.getFirst().y();
            out.set(0, new Point(input.getFirst().x(), average));
            out.add(new Point(input.getLast().x(), average));
        }
        return List.copyOf(out);
    }

    /** Ideal brick-wall low-pass filter in the DFT domain; cutoff is a nonnegative DFT bin. */
    private static List<Point> lowPass(List<Point> input, int cutoffHarmonic, BooleanSupplier cancelled) {
        if (cutoffHarmonic < 0) throw new IllegalArgumentException("Гармоника среза должна быть неотрицательной");
        int n = input.size();
        if (n > 10000) throw new IllegalArgumentException("Для прямого расчёта ФНЧ ограничьте сигнал 10000 точками");
        double dt = medianInterval(input);
        double tolerance = Math.max(1e-12, Math.abs(dt) * 1e-7);
        for (int i = 1; i < n - 1; i++) if (Math.abs((input.get(i + 1).x() - input.get(i).x()) - dt) > tolerance)
            throw new IllegalArgumentException("Спектральный ФНЧ требует равномерных временных отсчётов");
        int size = 1;
        while (size < n) size <<= 1;
        double[] real = new double[size], imag = new double[size];
        for (int i = 0; i < n; i++) real[i] = input.get(i).y();
        fft(real, imag, false, cancelled);
        double cutoffHz = cutoffHarmonic / (n * dt);
        for (int k = 0; k < size; k++)
            if (Math.min(k, size - k) / (size * dt) > cutoffHz) { real[k] = 0; imag[k] = 0; }
        fft(real, imag, true, cancelled);
        List<Point> out = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            checkCancelled(cancelled);
            out.add(new Point(input.get(j).x(), real[j]));
        }
        return out;
    }

    private static void fft(double[] real, double[] imag, boolean inverse, BooleanSupplier cancelled) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >>> 1;
            for (; (j & bit) != 0; bit >>>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double swap = real[i]; real[i] = real[j]; real[j] = swap;
                swap = imag[i]; imag[i] = imag[j]; imag[j] = swap;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            checkCancelled(cancelled);
            double angle = (inverse ? 2 : -2) * Math.PI / length;
            double stepRe = Math.cos(angle), stepIm = Math.sin(angle);
            for (int start = 0; start < n; start += length) {
                double wRe = 1, wIm = 0;
                for (int j = 0; j < length / 2; j++) {
                    int even = start + j, odd = even + length / 2;
                    double oddRe = real[odd] * wRe - imag[odd] * wIm;
                    double oddIm = real[odd] * wIm + imag[odd] * wRe;
                    real[odd] = real[even] - oddRe; imag[odd] = imag[even] - oddIm;
                    real[even] += oddRe; imag[even] += oddIm;
                    double next = wRe * stepRe - wIm * stepIm;
                    wIm = wRe * stepIm + wIm * stepRe; wRe = next;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { real[i] /= n; imag[i] /= n; }
    }

    private static double medianInterval(List<Point> input) {
        if (input.size() < 2) throw new IllegalArgumentException("Для определения временного шага нужны два отсчёта");
        double[] intervals = new double[input.size() - 1];
        for (int i = 0; i < intervals.length; i++) intervals[i] = input.get(i + 1).x() - input.get(i).x();
        java.util.Arrays.sort(intervals);
        return intervals[intervals.length / 2];
    }

    private record Compression(List<Point> knots, List<Point> reconstructed) { }

    private static Compression compress(List<Point> input, double aperture, int maxGap, String algorithm, BooleanSupplier cancelled) {
        if (!(aperture >= 0) || !Double.isFinite(aperture)) throw new IllegalArgumentException("Апертура должна быть неотрицательной");
        if (maxGap < 1) throw new IllegalArgumentException("Максимальный интервал должен быть не меньше 1");
        List<Point> knots = new ArrayList<>();
        knots.add(input.getFirst());
        int anchor = 0;
        while (anchor < input.size() - 1) {
            checkCancelled(cancelled);
            int accepted = anchor + 1;
            int limit = Math.min(input.size() - 1, anchor + maxGap);
            for (int candidate = anchor + 1; candidate <= limit; candidate++) {
                double maxDeviation = 0;
                Point a = input.get(anchor), b = input.get(candidate);
                for (int j = anchor + 1; j < candidate; j++) {
                    double u = (input.get(j).x() - a.x()) / (b.x() - a.x());
                    double predicted;
                    if (algorithm.equals("extrapolation-fixed")) predicted = input.getFirst().y();
                    else if (algorithm.equals("extrapolation-floating")) predicted = a.y();
                    else if (algorithm.equals("extrapolation-linear") && knots.size() > 1) {
                        Point previous = knots.get(knots.size() - 2);
                        double slope = (a.y() - previous.y()) / (a.x() - previous.x());
                        predicted = a.y() + slope * (input.get(j).x() - a.x());
                    } else predicted = algorithm.equals("compression-zero-order") ? a.y() : a.y() + u * (b.y() - a.y());
                    maxDeviation = Math.max(maxDeviation, Math.abs(input.get(j).y() - predicted));
                }
                if (algorithm.startsWith("extrapolation-") && candidate < input.size() - 1) {
                    double predicted;
                    if (algorithm.equals("extrapolation-fixed")) predicted = input.getFirst().y();
                    else if (algorithm.equals("extrapolation-linear") && knots.size() > 1) {
                        Point previous = knots.get(knots.size() - 2), anchorPoint = input.get(anchor);
                        predicted = anchorPoint.y() + (anchorPoint.y() - previous.y()) / (anchorPoint.x() - previous.x())
                            * (input.get(candidate).x() - anchorPoint.x());
                    } else predicted = input.get(anchor).y();
                    maxDeviation = Math.max(maxDeviation, Math.abs(input.get(candidate).y() - predicted));
                }
                if (maxDeviation > aperture && candidate > anchor + 1) break;
                accepted = candidate;
                if (maxDeviation > aperture) break;
            }
            if (accepted <= anchor) accepted = anchor + 1;
            anchor = accepted;
            knots.add(input.get(anchor));
        }
        List<Point> reconstructed;
        if (algorithm.equals("extrapolation-linear")) {
            reconstructed = extrapolateLinear(input, knots, cancelled);
        } else {
            List<Double> x = xValues(input);
            reconstructed = algorithm.equals("extrapolation-fixed")
                ? fixedApertureReconstruction(input, knots, cancelled)
                : algorithm.equals("compression-zero-order") || algorithm.equals("extrapolation-floating")
                    ? SignalMath.stepInterpolate(knots, x) : SignalMath.linearInterpolate(knots, x);
        }
        return new Compression(List.copyOf(knots), reconstructed);
    }

    private static List<Point> extrapolateLinear(List<Point> input, List<Point> knots, BooleanSupplier cancelled) {
        List<Point> output = new ArrayList<>(input.size());
        int transmitted = 0;
        for (Point point : input) {
            checkCancelled(cancelled);
            while (transmitted + 1 < knots.size() && knots.get(transmitted + 1).x() <= point.x()) transmitted++;
            Point anchor = knots.get(transmitted);
            double y = anchor.y();
            if (transmitted > 0) {
                Point previous = knots.get(transmitted - 1);
                double slope = (anchor.y() - previous.y()) / (anchor.x() - previous.x());
                y += slope * (point.x() - anchor.x());
            }
            output.add(new Point(point.x(), y));
        }
        return List.copyOf(output);
    }

    private static List<Point> fixedApertureReconstruction(List<Point> input, List<Point> knots, BooleanSupplier cancelled) {
        List<Point> output = new ArrayList<>(input.size());
        int transmitted = 0;
        double fixedCenter = knots.getFirst().y();
        for (Point point : input) {
            checkCancelled(cancelled);
            if (transmitted < knots.size() && knots.get(transmitted).x() == point.x()) {
                output.add(new Point(point.x(), knots.get(transmitted).y()));
                transmitted++;
            } else output.add(new Point(point.x(), fixedCenter));
        }
        return List.copyOf(output);
    }

    private static List<Double> xValues(List<Point> points) { return points.stream().map(Point::x).toList(); }
    private static double[] range(List<Point> input) {
        double min = input.stream().mapToDouble(Point::y).min().orElseThrow();
        double max = input.stream().mapToDouble(Point::y).max().orElseThrow();
        if (max == min) max = min + 1;
        return new double[]{min, max};
    }
    private static int integer(Map<String, Double> p, String name, int fallback) {
        double value = p.getOrDefault(name, (double) fallback);
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Параметр " + name + " должен быть целым числом");
        return (int) value;
    }
    private static double rmse(List<Point> expected, List<Point> actual) {
        if (expected.size() != actual.size()) throw new IllegalArgumentException("Для оценки ошибки сигналы должны иметь одинаковую длину");
        double sum = 0;
        for (int i = 0; i < expected.size(); i++) sum += Math.pow(expected.get(i).y() - actual.get(i).y(), 2);
        return Math.sqrt(sum / expected.size());
    }
    private static double maxError(List<Point> expected, List<Point> actual) {
        double max = 0;
        for (int i = 0; i < expected.size(); i++) max = Math.max(max, Math.abs(expected.get(i).y() - actual.get(i).y()));
        return max;
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Расчёт отменён");
    }
}
