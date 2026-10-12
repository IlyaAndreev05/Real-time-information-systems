package ru.ilyaandreev.rtis.core;

import ru.ilyaandreev.rtis.model.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SignalMath {
    private SignalMath() { }

    public static List<Point> sine(double amplitude, double frequency, double phase, double duration, int count) {
        requirePositive(duration, "Длительность");
        requireCount(count);
        if (frequency < 0 || !Double.isFinite(frequency)) throw new IllegalArgumentException("Частота должна быть неотрицательной");
        List<Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double t = duration * i / (count - 1.0);
            points.add(new Point(t, amplitude * Math.sin(2 * Math.PI * frequency * t + phase)));
        }
        return points;
    }

    public static List<Point> composite(double duration, int count, double... amplitudeFrequencyPhase) {
        if (amplitudeFrequencyPhase.length == 0 || amplitudeFrequencyPhase.length % 3 != 0)
            throw new IllegalArgumentException("Гармоники задаются тройками: амплитуда, частота, фаза");
        requirePositive(duration, "Длительность");
        requireCount(count);
        List<Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double t = duration * i / (count - 1.0);
            double y = 0;
            for (int j = 0; j < amplitudeFrequencyPhase.length; j += 3)
                y += amplitudeFrequencyPhase[j] * Math.sin(2 * Math.PI * amplitudeFrequencyPhase[j + 1] * t + amplitudeFrequencyPhase[j + 2]);
            points.add(new Point(t, y));
        }
        return points;
    }

    /** Reproducible test signal whose distribution and amplitudes are explicit in project metadata. */
    public static List<Point> distribution(double duration, int count, double min, double max,
                                           String law, long seed) {
        requirePositive(duration, "Длительность");
        requireCount(count);
        if (!Double.isFinite(min) || !Double.isFinite(max) || !(max > min))
            throw new IllegalArgumentException("Диапазон амплитуд должен быть конечным и ненулевым");
        if (!"uniform".equals(law) && !"normal".equals(law))
            throw new IllegalArgumentException("Закон распределения должен быть uniform или normal");
        Random random = new Random(seed);
        double mean = (min + max) / 2;
        double sigma = (max - min) / 6;
        List<Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double y;
            if ("uniform".equals(law)) y = min + random.nextDouble() * (max - min);
            else {
                // Truncated normal by rejection, avoiding artificial probability spikes at the limits.
                do { y = mean + sigma * random.nextGaussian(); } while (y < min || y > max);
            }
            points.add(new Point(duration * i / (count - 1.0), y));
        }
        return List.copyOf(points);
    }

    /** Deterministic multi-tone test input with phase and time units in radians and seconds. */
    public static List<Point> multiTone(double duration, int count, double... amplitudeFrequencyPhase) {
        return composite(duration, count, amplitudeFrequencyPhase);
    }

    public static double rmse(List<Point> expected, List<Point> actual) {
        requireSameGrid(expected, actual);
        double sum = 0;
        for (int i = 0; i < expected.size(); i++) sum += square(expected.get(i).y() - actual.get(i).y());
        return Math.sqrt(sum / expected.size());
    }

    public static double maxAbsoluteError(List<Point> expected, List<Point> actual) {
        requireSameGrid(expected, actual);
        double max = 0;
        for (int i = 0; i < expected.size(); i++) max = Math.max(max, Math.abs(expected.get(i).y() - actual.get(i).y()));
        return max;
    }

    public static double amplitudeRange(List<Point> points) {
        requireSignal(points);
        double min = points.stream().mapToDouble(Point::y).min().orElseThrow();
        double max = points.stream().mapToDouble(Point::y).max().orElseThrow();
        return max - min;
    }

    private static void requireSameGrid(List<Point> expected, List<Point> actual) {
        requireSignal(expected);
        requireSignal(actual);
        if (expected.size() != actual.size()) throw new IllegalArgumentException("Сигналы для сравнения должны иметь одинаковую длину");
        for (int i = 0; i < expected.size(); i++) if (Double.compare(expected.get(i).x(), actual.get(i).x()) != 0)
            throw new IllegalArgumentException("Сигналы для сравнения должны иметь одинаковые временные координаты");
    }

    private static double square(double value) { return value * value; }

    public static List<Point> sample(List<Point> input, int stride) {
        requireSignal(input);
        if (stride < 1) throw new IllegalArgumentException("Шаг дискретизации должен быть не меньше 1");
        List<Point> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i += stride) result.add(input.get(i));
        if (!result.getLast().equals(input.getLast())) result.add(input.getLast());
        return result;
    }

    /** Builds an almost-uniform display grid with every manually entered knot as a grid point. */
    public static List<Double> interpolationGrid(List<Point> knots, int sampleCount) {
        requireSignal(knots);
        if (sampleCount < 2 || sampleCount > 1_000_000)
            throw new IllegalArgumentException("Число точек сетки должно быть от 2 до 1 000 000");
        int segmentCount = knots.size() - 1;
        int targetCount = Math.max(sampleCount, knots.size());
        int extraIntervals = targetCount - 1 - segmentCount;
        double totalSpan = knots.getLast().x() - knots.getFirst().x();
        if (!Double.isFinite(totalSpan) || !(totalSpan > 0))
            throw new IllegalArgumentException("Диапазон координат слишком велик для построения сетки");
        int[] intervals = new int[segmentCount];
        List<Integer> remainderOrder = new ArrayList<>(segmentCount);
        double[] remainders = new double[segmentCount];
        int allocatedExtras = 0;
        for (int i = 0; i < segmentCount; i++) {
            double exactExtra = extraIntervals * (knots.get(i + 1).x() - knots.get(i).x()) / totalSpan;
            int wholeExtra = (int) Math.floor(exactExtra);
            intervals[i] = 1 + wholeExtra;
            remainders[i] = exactExtra - wholeExtra;
            allocatedExtras += wholeExtra;
            remainderOrder.add(i);
        }
        remainderOrder.sort((left, right) -> Double.compare(remainders[right], remainders[left]));
        for (int i = 0; i < extraIntervals - allocatedExtras; i++) intervals[remainderOrder.get(i)]++;

        List<Double> grid = new ArrayList<>(targetCount);
        for (int i = 0; i < segmentCount; i++) {
            Point start = knots.get(i), end = knots.get(i + 1);
            for (int j = 0; j < intervals[i]; j++)
                grid.add(j == 0 ? start.x() : start.x() + (end.x() - start.x()) * j / intervals[i]);
        }
        grid.add(knots.getLast().x());
        return List.copyOf(grid);
    }

    public static List<Point> quantize(List<Point> input, int bits, double min, double max) {
        requireSignal(input);
        if (bits < 1 || bits > 24) throw new IllegalArgumentException("Разрядность должна быть от 1 до 24");
        if (!(max > min)) throw new IllegalArgumentException("Верхняя граница должна быть больше нижней");
        long levels = (1L << bits) - 1;
        double step = (max - min) / levels;
        return input.stream().map(p -> new Point(p.x(), Math.max(min, Math.min(max,
            min + Math.rint((p.y() - min) / step) * step)))).toList();
    }

    public static List<Point> addGaussianNoise(List<Point> input, double standardDeviation, long seed) {
        requireSignal(input);
        if (standardDeviation < 0 || !Double.isFinite(standardDeviation)) throw new IllegalArgumentException("Отклонение шума должно быть неотрицательным");
        Random random = new Random(seed);
        return input.stream().map(p -> new Point(p.x(), p.y() + random.nextGaussian() * standardDeviation)).toList();
    }

    public static List<Point> stepInterpolate(List<Point> knots, List<Double> xValues) {
        requireSignal(knots);
        List<Point> out = new ArrayList<>(xValues.size());
        for (double x : xValues) out.add(new Point(x, knots.get(x >= knots.getLast().x() ? knots.size() - 1 : segment(knots, x)).y()));
        return out;
    }

    public static List<Point> linearInterpolate(List<Point> knots, List<Double> xValues) {
        requireSignal(knots);
        List<Point> out = new ArrayList<>(xValues.size());
        for (double x : xValues) {
            int i = segment(knots, x);
            Point a = knots.get(i), b = knots.get(Math.min(i + 1, knots.size() - 1));
            double u = b.x() == a.x() ? 0 : (x - a.x()) / (b.x() - a.x());
            out.add(new Point(x, a.y() + u * (b.y() - a.y())));
        }
        return out;
    }

    /** Returns all exact and linearly interpolated zero crossings without duplicating shared endpoints. */
    public static List<Point> zeroCrossings(List<Point> points) {
        requireSignal(points);
        List<Point> crossings = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i);
            if (a.y() == 0.0) addUniqueCrossing(crossings, a);
            if (i + 1 < points.size()) {
                Point b = points.get(i + 1);
                if ((a.y() < 0 && b.y() > 0) || (a.y() > 0 && b.y() < 0)) {
                    double x = a.x() + (-a.y()) * (b.x() - a.x()) / (b.y() - a.y());
                    addUniqueCrossing(crossings, new Point(x, 0));
                }
            }
        }
        return List.copyOf(crossings);
    }

    /** Linearly evaluates a signal at x; values outside the measured interval are rejected. */
    public static Point interpolateAt(List<Point> points, double x) {
        requireSignal(points);
        if (!Double.isFinite(x) || x < points.getFirst().x() || x > points.getLast().x())
            throw new IllegalArgumentException("Координата курсора должна быть в диапазоне сигнала");
        int i = segment(points, x);
        Point a = points.get(i), b = points.get(Math.min(i + 1, points.size() - 1));
        double ratio = b.x() == a.x() ? 0 : (x - a.x()) / (b.x() - a.x());
        return new Point(x, a.y() + ratio * (b.y() - a.y()));
    }

    private static void addUniqueCrossing(List<Point> crossings, Point point) {
        if (crossings.isEmpty() || crossings.getLast().x() != point.x()) crossings.add(point);
    }

    public static List<Point> cubicSpline(List<Point> knots, List<Double> xValues) {
        requireSignal(knots);
        int n = knots.size();
        if (n < 3) return linearInterpolate(knots, xValues);
        double[] h = new double[n - 1], alpha = new double[n], l = new double[n], mu = new double[n], z = new double[n], c = new double[n];
        double[] a = knots.stream().mapToDouble(Point::y).toArray();
        double[] b = new double[n - 1], d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) h[i] = knots.get(i + 1).x() - knots.get(i).x();
        for (int i = 1; i < n - 1; i++) alpha[i] = 3 * (a[i + 1] - a[i]) / h[i] - 3 * (a[i] - a[i - 1]) / h[i - 1];
        l[0] = l[n - 1] = 1;
        for (int i = 1; i < n - 1; i++) {
            l[i] = 2 * (knots.get(i + 1).x() - knots.get(i - 1).x()) - h[i - 1] * mu[i - 1];
            mu[i] = h[i] / l[i];
            z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i];
        }
        for (int j = n - 2; j >= 0; j--) {
            c[j] = z[j] - mu[j] * c[j + 1];
            b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2 * c[j]) / 3;
            d[j] = (c[j + 1] - c[j]) / (3 * h[j]);
        }
        List<Point> out = new ArrayList<>(xValues.size());
        for (double x : xValues) {
            int i = segment(knots, x);
            double dx = x - knots.get(i).x();
            out.add(new Point(x, a[i] + b[i] * dx + c[i] * dx * dx + d[i] * dx * dx * dx));
        }
        return out;
    }

    private static int segment(List<Point> knots, double x) {
        int low = 0, high = knots.size() - 1;
        while (low < high - 1) {
            int mid = (low + high) >>> 1;
            if (knots.get(mid).x() <= x) low = mid; else high = mid;
        }
        return Math.min(low, knots.size() - 2);
    }

    public static void requireSignal(List<Point> points) {
        if (points == null || points.size() < 2) throw new IllegalArgumentException("Сигнал должен содержать не менее двух точек");
        double prev = Double.NEGATIVE_INFINITY;
        for (Point point : points) {
            if (point.x() <= prev) throw new IllegalArgumentException("Временные координаты сигнала должны строго возрастать");
            prev = point.x();
        }
    }

    private static void requireCount(int count) {
        if (count < 2 || count > 1_000_000) throw new IllegalArgumentException("Число точек должно быть от 2 до 1 000 000");
    }

    private static void requirePositive(double value, String name) {
        if (!(value > 0) || !Double.isFinite(value)) throw new IllegalArgumentException(name + " должна быть положительной");
    }
}
