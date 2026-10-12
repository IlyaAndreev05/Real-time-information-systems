package ru.ilyaandreev.rtis.core;

import java.util.List;
import java.util.Map;

/** Names and parameter metadata for available operations. Laboratory numbers are optional tags. */
public final class AlgorithmCatalog {
    private AlgorithmCatalog() { }

    public record Algorithm(String id, String moduleId, String name, String evidence, String status,
                            List<ParameterSpec> parameters) {
        public Algorithm { parameters = List.copyOf(parameters); }
    }
    public record Module(String id, String name, Integer labTag, List<String> algorithms) {
        public Module { algorithms = List.copyOf(algorithms); }
    }

    private static ParameterSpec p(String id, String name, String unit, double min, double max,
                                   double value, double step, boolean integer) {
        return new ParameterSpec(id, name, unit, min, max, value, step, integer);
    }
    private static final ParameterSpec STRIDE = p("stride", "Шаг дискретизации", "отсчётов", 1, 10_000, 4, 1, true);
    private static final ParameterSpec BITS = p("bits", "Разрядность АЦП", "бит", 1, 24, 8, 1, true);
    private static final ParameterSpec WINDOW = p("window", "Ширина окна", "отсчётов", 1, 10_000, 5, 1, true);
    private static final ParameterSpec ALPHA = p("alpha", "Коэффициент сглаживания", "", 0.01, 1, 0.25, 0.1, false);
    private static final ParameterSpec NOISE = p("standardDeviation", "Среднеквадратичное отклонение шума", "ед. амплитуды", 0, 100, 0.1, 0.1, false);
    private static final ParameterSpec APERTURE = p("aperture", "Апертура", "ед. амплитуды", 0, 100, 0.1, 0.1, false);
    private static final ParameterSpec MAX_GAP = p("maxGap", "Максимальный интервал", "отсчётов", 1, 10_000, 32, 1, true);

    public static final List<Module> MODULES = List.of(
        new Module("spectrum", "Спектральные характеристики", 1, List.of("fourier", "autocorrelation")),
        new Module("sampling", "Дискретизация и квантование", 2, List.of("sample-hold", "linear-reconstruction", "quantize")),
        new Module("conversion", "АЦП и ЦАП", 3, List.of("adc-dac", "quantization-error")),
        new Module("processing", "Первичная обработка", 4, List.of("step-interpolation", "linear-interpolation", "cubic-spline", "moving-average", "first-order-filter", "add-noise")),
        new Module("compression", "Сжатие и восстановление", 5, List.of("compression-zero-order", "compression-linear", "extrapolation-fixed", "extrapolation-floating", "extrapolation-linear"))
    );

    public static final Map<String, Algorithm> ALGORITHMS = Map.ofEntries(
        entry("fourier", "spectrum", "Ряд Фурье и амплитудный спектр", "Методичка №1; обработчик btnShowAMFClick найден в Signal.exe", "частично исследовано", List.of()),
        entry("autocorrelation", "spectrum", "Корреляционная характеристика", "Методичка №1", "найдено в методичке", List.of()),
        entry("sample-hold", "sampling", "Ступенчатая дискретизация", "Методичка №2; menuInterpol_Step найден в SRV.exe", "частично исследовано", List.of(STRIDE)),
        entry("linear-reconstruction", "sampling", "Линейное восстановление", "Методички №1–3; menuInterpol_Line найден в SRV.exe", "частично исследовано", List.of(STRIDE)),
        entry("quantize", "sampling", "Квантование уровня", "Методички №2–3", "найдено в методичке", List.of(BITS)),
        entry("adc-dac", "conversion", "Модель АЦП/ЦАП", "Методичка №3; функции LabBench.exe требуют исследования", "найдено в методичке", List.of(BITS)),
        entry("quantization-error", "conversion", "Оценка погрешности квантования", "Методичка №3", "найдено в методичке", List.of(BITS)),
        entry("step-interpolation", "processing", "Интерполяция нулевого порядка", "Методичка №4; DoStepInterpolation найден в SRV.exe", "частично исследовано", List.of(STRIDE)),
        entry("linear-interpolation", "processing", "Линейная интерполяция", "Методички №1 и №4; DoLineInterpolation найден в SRV.exe", "частично исследовано", List.of(STRIDE)),
        entry("cubic-spline", "processing", "Кубический сплайн", "DoSplineInterpolation найден в SRV.exe", "найдено в двоичных строках", List.of(STRIDE)),
        entry("moving-average", "processing", "Скользящее среднее", "SlideMiddleFiltering найден в SRV.exe", "найдено в двоичных строках", List.of(WINDOW)),
        entry("first-order-filter", "processing", "Цифровой ФНЧ первого порядка", "Методичка №4; DigitalFilterFiltering найден в SRV.exe", "частично исследовано", List.of(ALPHA)),
        entry("add-noise", "processing", "Добавление шума", "SignalWithHindrance найден в SRV.exe", "найдено в двоичных строках", List.of(NOISE)),
        entry("compression-zero-order", "compression", "Апертурное сжатие нулевого порядка", "Методичка №5: ПИНП", "найдено в методичке", List.of(APERTURE, MAX_GAP)),
        entry("compression-linear", "compression", "Апертурное сжатие первого порядка", "Методичка №5: ПИПП", "найдено в методичке", List.of(APERTURE, MAX_GAP)),
        entry("extrapolation-fixed", "compression", "Экстраполяция нулевого порядка, фиксированная апертура", "Методичка №5: ЭНП–ФА", "найдено в методичке", List.of(APERTURE, MAX_GAP)),
        entry("extrapolation-floating", "compression", "Экстраполяция нулевого порядка, плавающая апертура", "Методичка №5: ЭНП–ПА", "найдено в методичке", List.of(APERTURE, MAX_GAP)),
        entry("extrapolation-linear", "compression", "Экстраполяция первого порядка", "Методичка №5: ЭПП", "найдено в методичке", List.of(APERTURE, MAX_GAP))
    );

    private static Map.Entry<String, Algorithm> entry(String id, String module, String name, String evidence,
                                                       String status, List<ParameterSpec> parameters) {
        return Map.entry(id, new Algorithm(id, module, name, evidence, status, parameters));
    }
}
