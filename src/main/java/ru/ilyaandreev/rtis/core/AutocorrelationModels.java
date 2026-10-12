package ru.ilyaandreev.rtis.core;

import ru.ilyaandreev.rtis.model.Point;

import java.util.ArrayList;
import java.util.List;

/** Ten one-parameter normalized autocorrelation models from RTIS manual 2, table 2. */
public final class AutocorrelationModels {
    public record ModelFit(int model, double alpha, double rmse, double maxAbsoluteError, List<Point> curve) {
        public ModelFit { curve = List.copyOf(curve); }
    }
    public record Fit(List<Point> empirical, List<ModelFit> models, List<ModelFit> minimaxModels) {
        public Fit { empirical = List.copyOf(empirical); models = List.copyOf(models); minimaxModels = List.copyOf(minimaxModels); }
    }

    private AutocorrelationModels() { }

    /** Unbiased, mean-centered empirical ACF is normalized by R(0), as in the manual's fitting procedure. */
    public static Fit fit(List<Point> signal, double maxLagFraction, double alphaMin, double alphaMax, int gridSteps) {
        SignalMath.requireSignal(signal);
        if (!(maxLagFraction > 0 && maxLagFraction <= 1)) throw new IllegalArgumentException("Доля интервалов лага должна быть в (0; 1]");
        if (!(alphaMin > 0) || !(alphaMax > alphaMin) || !Double.isFinite(alphaMax)) throw new IllegalArgumentException("Нужен положительный диапазон alpha");
        if (gridSteps < 20 || gridSteps > 10000) throw new IllegalArgumentException("Сетка alpha должна иметь от 20 до 10000 шагов");
        int n = signal.size();
        int lagCount = Math.min(n, Math.max(2, (int) Math.floor((n - 1) * maxLagFraction) + 1));
        double mean = signal.stream().mapToDouble(Point::y).average().orElse(0);
        double r0 = covariance(signal, mean, 0);
        if (!(r0 > 0)) throw new IllegalArgumentException("АКФ не определена для сигнала с нулевой дисперсией");
        List<Point> empirical = new ArrayList<>(lagCount);
        double[] lags = new double[lagCount], values = new double[lagCount];
        double x0 = signal.getFirst().x();
        for (int lag = 0; lag < lagCount; lag++) {
            lags[lag] = signal.get(lag).x() - x0;
            values[lag] = covariance(signal, mean, lag) / r0;
            empirical.add(new Point(lags[lag], values[lag]));
        }
        List<ModelFit> fits = new ArrayList<>(10), minimaxFits = new ArrayList<>(10);
        double logMin = Math.log(alphaMin), logMax = Math.log(alphaMax);
        for (int model = 1; model <= 10; model++) {
            fits.add(optimize(model, lags, values, logMin, logMax, gridSteps, false));
            minimaxFits.add(optimize(model, lags, values, logMin, logMax, gridSteps, true));
        }
        return new Fit(empirical, fits, minimaxFits);
    }

    private static ModelFit optimize(int model, double[] lags, double[] empirical, double logMin, double logMax,
                                     int gridSteps, boolean minimax) {
        double bestLog = logMin, bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= gridSteps; i++) {
            double candidate = logMin + (logMax - logMin) * i / gridSteps;
            double score = score(model, Math.exp(candidate), lags, empirical, minimax);
            if (score < bestScore) { bestScore = score; bestLog = candidate; }
        }
        double step = (logMax - logMin) / gridSteps;
        double left = Math.max(logMin, bestLog - step), right = Math.min(logMax, bestLog + step);
        // Refine the best log-grid neighborhood with a golden-section minimizer.
        for (int iteration = 0; iteration < 50; iteration++) {
            double c = right - (right - left) / 1.618033988749895;
            double d = left + (right - left) / 1.618033988749895;
            if (score(model, Math.exp(c), lags, empirical, minimax) <= score(model, Math.exp(d), lags, empirical, minimax)) right = d;
            else left = c;
        }
        double refined = (left + right) / 2;
        if (score(model, Math.exp(bestLog), lags, empirical, minimax) < score(model, Math.exp(refined), lags, empirical, minimax)) refined = bestLog;
        double alpha = Math.exp(refined), sum = 0, maximum = 0;
        List<Point> curve = new ArrayList<>(lags.length);
        for (int i = 0; i < lags.length; i++) {
            double theoretical = value(model, alpha * Math.abs(lags[i]));
            double error = Math.abs(empirical[i] - theoretical);
            sum += error * error; maximum = Math.max(maximum, error);
            curve.add(new Point(lags[i], theoretical));
        }
        return new ModelFit(model, alpha, Math.sqrt(sum / lags.length), maximum, curve);
    }

    public static double value(int model, double x) {
        if (model < 1 || model > 10) throw new IllegalArgumentException("Номер модели АКФ должен быть 1–10");
        x = Math.abs(x);
        double sinc = x == 0 ? 1 : Math.sin(x) / x;
        return switch (model) {
            case 1 -> Math.exp(-x);
            case 2 -> (1 + x) * Math.exp(-x);
            case 3 -> Math.exp(-x * x);
            case 4 -> sinc;
            case 5 -> Math.max(1 - x, 0);
            case 6 -> sinc * sinc;
            case 7 -> x == 0 ? 1 : (Math.sin(2 * x) - Math.sin(x)) / x;
            case 8 -> Math.exp(-x) * (Math.cos(x) + Math.sin(x));
            case 9 -> Math.abs(1 - 4 * x * x / (Math.PI * Math.PI)) < 1e-10
                ? Math.PI / 4 : Math.cos(x) / (1 - 4 * x * x / (Math.PI * Math.PI));
            case 10 -> Math.exp(-x) * Math.cos(x);
            default -> throw new IllegalStateException();
        };
    }

    private static double covariance(List<Point> signal, double mean, int lag) {
        double sum = 0;
        for (int i = 0; i < signal.size() - lag; i++) sum += (signal.get(i).y() - mean) * (signal.get(i + lag).y() - mean);
        return sum / (signal.size() - lag);
    }

    private static double score(int model, double alpha, double[] lags, double[] empirical, boolean minimax) {
        double sum = 0, maximum = 0;
        for (int i = 0; i < lags.length; i++) {
            double error = empirical[i] - value(model, alpha * Math.abs(lags[i]));
            if (!Double.isFinite(error)) return Double.POSITIVE_INFINITY;
            sum += error * error; maximum = Math.max(maximum, Math.abs(error));
        }
        return minimax ? maximum : sum / lags.length;
    }
}
