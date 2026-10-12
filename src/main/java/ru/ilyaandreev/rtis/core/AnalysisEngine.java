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
    private AnalysisEngine() { }

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
                List<Point> knots = SignalMath.sample(source, integer(p, "stride", 4));
                output = algorithm.equals("sample-hold") ? SignalMath.stepInterpolate(knots, xValues(source)) : SignalMath.linearInterpolate(knots, xValues(source));
                metrics.put("узловыхТочек", (double) knots.size());
            }
            case "quantize", "adc-dac", "quantization-error" -> {
                int bits = integer(p, "bits", 8);
                double[] range = range(source);
                double min = p.getOrDefault("min", range[0]), max = p.getOrDefault("max", range[1]);
                output = SignalMath.quantize(source, bits, min, max);
                metrics.put("бит", (double) bits);
                metrics.put("шагКвантования", (max - min) / ((1L << bits) - 1));
                metrics.put("теоретическаяМаксПогрешность", (max - min) / (2 * ((1L << bits) - 1)));
            }
            case "step-interpolation", "linear-interpolation", "cubic-spline" -> {
                List<Point> knots = SignalMath.sample(source, integer(p, "stride", 4));
                List<Double> xs = xValues(source);
                output = switch (algorithm) {
                    case "step-interpolation" -> SignalMath.stepInterpolate(knots, xs);
                    case "linear-interpolation" -> SignalMath.linearInterpolate(knots, xs);
                    default -> SignalMath.cubicSpline(knots, xs);
                };
                metrics.put("узловыхТочек", (double) knots.size());
            }
            case "add-noise" -> output = SignalMath.addGaussianNoise(source, p.getOrDefault("standardDeviation", 0.1), project.getNoiseSeed());
            case "moving-average" -> output = movingAverage(source, integer(p, "window", 5), cancelled);
            case "first-order-filter" -> output = firstOrderFilter(source, p.getOrDefault("alpha", 0.25), cancelled);
            case "compression-zero-order", "compression-linear", "extrapolation-fixed", "extrapolation-floating", "extrapolation-linear" -> {
                double aperture = p.getOrDefault("aperture", 0.1);
                Compression compression = compress(source, aperture, integer(p, "maxGap", 32), algorithm, cancelled);
                output = compression.knots();
                metrics.put("коэффициентСжатия", source.size() / (double) output.size());
                metrics.put("исходныхТочек", (double) source.size());
                metrics.put("сохранённыхТочек", (double) output.size());
                metrics.put("апертура", aperture);
                metrics.put("среднеквадратичнаяОшибка", rmse(source, compression.reconstructed()));
                metrics.put("максимальнаяОшибка", maxError(source, compression.reconstructed()));
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
        if (window < 1 || window > input.size()) throw new IllegalArgumentException("Окно фильтра должно быть от 1 до числа точек");
        List<Point> out = new ArrayList<>(input.size());
        double sum = 0;
        for (int i = 0; i < input.size(); i++) {
            checkCancelled(cancelled);
            sum += input.get(i).y();
            if (i >= window) sum -= input.get(i - window).y();
            out.add(new Point(input.get(i).x(), sum / Math.min(i + 1, window)));
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
                    double predicted = algorithm.equals("compression-zero-order") || algorithm.equals("extrapolation-fixed") || algorithm.equals("extrapolation-floating")
                        ? a.y() : a.y() + u * (b.y() - a.y());
                    maxDeviation = Math.max(maxDeviation, Math.abs(input.get(j).y() - predicted));
                }
                if (maxDeviation > aperture && candidate > anchor + 1) break;
                accepted = candidate;
                if (maxDeviation > aperture) break;
            }
            if (accepted <= anchor) accepted = anchor + 1;
            anchor = accepted;
            knots.add(input.get(anchor));
        }
        List<Double> x = xValues(input);
        List<Point> reconstructed = algorithm.equals("compression-zero-order") || algorithm.equals("extrapolation-fixed") || algorithm.equals("extrapolation-floating")
            ? SignalMath.stepInterpolate(knots, x) : SignalMath.linearInterpolate(knots, x);
        return new Compression(List.copyOf(knots), reconstructed);
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
