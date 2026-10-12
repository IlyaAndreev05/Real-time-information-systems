package ru.ilyaandreev.rtis.core;

import ru.ilyaandreev.rtis.model.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reproducible ADC/DAC model. DNL uses a monotone, endpoint-preserving code-width profile. */
public final class AdcModel {
    public record Conversion(List<Point> samples, List<Point> reconstructed, Map<String, Double> metrics) {
        public Conversion { samples = List.copyOf(samples); reconstructed = List.copyOf(reconstructed); metrics = Map.copyOf(metrics); }
    }

    private AdcModel() { }

    public static Conversion convert(List<Point> input, double sampleInterval, int bits, double fullScaleMin,
                                     double fullScaleMax, String reconstruction, String errorType,
                                     double errorPercent, int differentialBit) {
        SignalMath.requireSignal(input);
        if (!(sampleInterval > 0) || !Double.isFinite(sampleInterval)) throw new IllegalArgumentException("Интервал дискретизации должен быть положительным");
        if (bits < 1 || bits > 24) throw new IllegalArgumentException("Разрядность должна быть от 1 до 24");
        if (!(fullScaleMax > fullScaleMin) || !Double.isFinite(fullScaleMin) || !Double.isFinite(fullScaleMax))
            throw new IllegalArgumentException("Пределы АЦП должны задавать конечный диапазон");
        if (errorPercent < 0 || errorPercent > 100 || !Double.isFinite(errorPercent))
            throw new IllegalArgumentException("Величина инструментальной ошибки должна быть от 0 до 100 процентов");
        if (!List.of("step", "linear").contains(reconstruction)) throw new IllegalArgumentException("Восстановление должно быть step или linear");
        if (!List.of("none", "additive", "multiplicative", "integral", "differential", "combined").contains(errorType))
            throw new IllegalArgumentException("Неизвестная модель инструментальной ошибки");
        if (differentialBit < 0 || differentialBit > 2) throw new IllegalArgumentException("Номер старшего разряда должен быть 0, 1 или 2");
        if (("differential".equals(errorType) || "combined".equals(errorType)) && bits < 4)
            throw new IllegalArgumentException("Для нормируемой DNL трёх старших разрядов требуется не менее 4 бит");

        long levels = (1L << bits) - 1;
        double lsb = (fullScaleMax - fullScaleMin) / levels;
        // In the combined experiment the total percentage is split equally among offset, gain, INL and DNL.
        double differentialPercent = "combined".equals(errorType) ? errorPercent / 4 : errorPercent;
        double[] transferLevels = "differential".equals(errorType) || "combined".equals(errorType)
            ? differentialCodeLevels(bits, fullScaleMin, fullScaleMax, differentialPercent, differentialBit)
            : idealCodeLevels(bits, fullScaleMin, fullScaleMax);
        List<Point> samples = new ArrayList<>();
        for (double x = input.getFirst().x(); x < input.getLast().x(); x += sampleInterval) {
            double source = SignalMath.interpolateAt(input, x).y();
            samples.add(new Point(x, output(source, fullScaleMin, fullScaleMax, bits, errorType, errorPercent, differentialBit, transferLevels)));
            if (samples.size() > 1_000_000) throw new IllegalArgumentException("Частота дискретизации создаёт больше миллиона отсчётов");
        }
        if (samples.isEmpty() || samples.getLast().x() < input.getLast().x()) {
            double x = input.getLast().x();
            samples.add(new Point(x, output(input.getLast().y(), fullScaleMin, fullScaleMax, bits, errorType, errorPercent, differentialBit, transferLevels)));
        }
        List<Double> xValues = input.stream().map(Point::x).toList();
        List<Point> restored = "linear".equals(reconstruction)
            ? SignalMath.linearInterpolate(samples, xValues) : SignalMath.stepInterpolate(samples, xValues);
        Map<String, Double> metrics = Map.of(
            "числоОтсчетов", (double) samples.size(), "интервалДискретизацииС", sampleInterval,
            "частотаДискретизацииГц", 1 / sampleInterval, "разрядностьБит", (double) bits,
            "шагКвантованияВ", lsb, "СКОВ", SignalMath.rmse(input, restored),
            "максимальнаяОшибкаВ", SignalMath.maxAbsoluteError(input, restored),
            "ошибкаОтносительноШкалыПроц", 100 * SignalMath.rmse(input, restored) / (fullScaleMax - fullScaleMin));
        return new Conversion(samples, restored, metrics);
    }

    /** Construct a monotone DAC level table whose selected upper-bit transitions have the requested DNL. */
    public static double[] differentialCodeLevels(int bits, double min, double max, double errorPercent, int bit) {
        if (bits < 4 || bits > 24) throw new IllegalArgumentException("Для трёх старших разрядов нужен АЦП не менее 4 бит");
        if (!(max > min) || !Double.isFinite(min) || !Double.isFinite(max)) throw new IllegalArgumentException("Пределы шкалы должны задавать положительный диапазон");
        if (bit < 0 || bit > 2) throw new IllegalArgumentException("Номер старшего разряда должен быть 0, 1 или 2");
        if (errorPercent < 0 || errorPercent > 100 || !Double.isFinite(errorPercent)) throw new IllegalArgumentException("DNL должна быть от 0 до 100 % полной шкалы");
        long count = (1L << bits) - 1;
        long interval = 1L << (bits - 1 - bit);
        long specialCount = count / interval;
        if (specialCount == 0 || specialCount >= count) throw new IllegalArgumentException("Для нормировки DNL нужны обычные и особые кванты");
        double span = max - min, idealStep = span / count;
        double delta = span * errorPercent / 100;
        double ordinaryStep = idealStep - specialCount * delta / (count - specialCount);
        if (!(ordinaryStep > 0)) throw new IllegalArgumentException("Профиль DNL приводит к неположительной ширине кванта; уменьшите ошибку или увеличьте разрядность");
        double[] result = new double[(int) count + 1];
        result[0] = min;
        for (long code = 1; code <= count; code++) {
            double width = code % interval == 0 ? idealStep + delta : ordinaryStep;
            result[(int) code] = result[(int) code - 1] + width;
        }
        result[result.length - 1] = max; // eliminate only accumulated floating-point roundoff
        return result;
    }

    private static double[] idealCodeLevels(int bits, double min, double max) {
        int count = (1 << bits) - 1;
        double step = (max - min) / count;
        double[] levels = new double[count + 1];
        for (int code = 0; code <= count; code++) levels[code] = min + code * step;
        levels[count] = max;
        return levels;
    }

    private static double output(double value, double min, double max, int bits, String type, double percent, int bit, double[] transferLevels) {
        long levels = (1L << bits) - 1;
        double span = max - min;
        double fraction = percent / 100;
        double clipped = Math.max(min, Math.min(max, value));
        long code = nearestCode(clipped, transferLevels);
        double quantized = transferLevels[(int) code];
        // Additive: constant offset; multiplicative: gain deviation proportional to the output.
        if ("none".equals(type) || percent == 0) return quantized;
        if ("additive".equals(type)) return clip(quantized + span * fraction, min, max);
        if ("multiplicative".equals(type)) return clip(quantized + quantized * fraction, min, max);
        double normalized = (quantized - min) / span;
        // One endpoint-anchored sinusoidal INL profile represents nonlinear deviation after offset/gain removal.
        if ("integral".equals(type)) return clip(quantized + span * fraction * Math.sin(2 * Math.PI * normalized), min, max);
        double part = percent / 4;
        if ("differential".equals(type)) return quantized;
        return clip(quantized + span * part / 100 + quantized * part / 100
            + span * part / 100 * Math.sin(2 * Math.PI * normalized), min, max);
    }

    private static int nearestCode(double value, double[] levels) {
        int low = 0, high = levels.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            double threshold = (levels[mid] + levels[mid + 1]) / 2;
            if (value < threshold) high = mid; else low = mid + 1; // ties round upward
        }
        return low;
    }

    private static double clip(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
