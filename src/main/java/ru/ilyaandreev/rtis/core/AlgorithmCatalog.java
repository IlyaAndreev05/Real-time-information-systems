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
    private static final ParameterSpec ORDER = p("order", "Параметр оптимального фильтра", "", 0, 14, 4, 1, true);
    private static final ParameterSpec FIRST_STEP = p("firstStep", "Число выборок первого шага", "отсчётов", 1, 200, 10, 1, true);
    private static final ParameterSpec SECOND_STEP = p("secondStep", "Число выборок второго шага", "отсчётов", 1, 50, 5, 1, true);
    private static final ParameterSpec CUTOFF = p("cutoffHarmonic", "Гармоника среза ФНЧ", "номер", 0, 5000, 10, 1, true);
    private static final ParameterSpec SAMPLE_INTERVAL = p("sampleInterval", "Интервал дискретизации", "с", 0.000001, 1000, 0.01, 0.001, false);
    private static final ParameterSpec FULL_SCALE_MIN = p("fullScaleMin", "Нижний предел шкалы АЦП", "В", -1_000_000, 1_000_000, 0, 0.1, false);
    private static final ParameterSpec FULL_SCALE_MAX = p("fullScaleMax", "Верхний предел шкалы АЦП", "В", -1_000_000, 1_000_000, 10, 0.1, false);
    private static final ParameterSpec ERROR_PERCENT = p("errorPercent", "Инструментальная ошибка (для DNL — % полной шкалы)", "%", 0, 100, 0, 0.1, false);
    private static final ParameterSpec DIFFERENTIAL_BIT = p("differentialBit", "Старший разряд с дифференциальной ошибкой", "индекс от старшего", 0, 2, 0, 1, true);
    private static final ParameterSpec ERROR_MODE = p("errorMode", "Модель ошибки: 0 идеальная, 1 аддитивная, 2 мультипликативная, 3 интегральная, 4 дифференциальная, 5 комплексная", "код", 0, 5, 0, 1, true);
    private static final ParameterSpec RECONSTRUCTION_MODE = p("reconstructionMode", "Восстановление: 0 ступенчатое, 1 линейное", "код", 0, 1, 0, 1, true);

    public static final List<Module> MODULES = List.of(
        new Module("spectrum", "Спектральные характеристики", null, List.of("fourier", "autocorrelation")),
        new Module("sampling", "Дискретизация и квантование", 1, List.of("sample-hold", "linear-reconstruction", "quantize")),
        new Module("conversion", "АЦП и ЦАП", 2, List.of("adc-dac", "quantization-error")),
        new Module("processing", "Первичная обработка", 3, List.of("step-interpolation", "linear-interpolation", "cubic-spline", "moving-average", "first-order-filter", "optimal-filter", "progressive-interpolation", "low-pass", "add-noise")),
        new Module("compression", "Сжатие и восстановление", 4, List.of("compression-zero-order", "compression-linear", "extrapolation-fixed", "extrapolation-floating", "extrapolation-linear"))
    );

    public static final Map<String, Algorithm> ALGORITHMS = Map.ofEntries(
        entry("fourier", "spectrum", "Ряд Фурье и амплитудный спектр", "Методичка №1; обработчик btnShowAMFClick найден в Signal.exe", "частично исследовано", List.of()),
        entry("autocorrelation", "spectrum", "Корреляционная характеристика", "Методичка №1", "найдено в методичке", List.of()),
        entry("sample-hold", "sampling", "Ступенчатая дискретизация", "Методичка №2; menuInterpol_Step найден в SRV.exe", "частично исследовано", List.of(STRIDE)),
        entry("linear-reconstruction", "sampling", "Линейное восстановление", "Методички №1–3; menuInterpol_Line найден в SRV.exe", "частично исследовано", List.of(STRIDE)),
        entry("quantize", "sampling", "Квантование уровня", "Равномерный идеальный квантователь", "реализовано", List.of(BITS, FULL_SCALE_MIN, FULL_SCALE_MAX)),
        entry("adc-dac", "conversion", "Модель АЦП/ЦАП", "Идеальная дискретизация и квантование с задаваемыми ошибками преобразования", "реализовано как явная математическая модель", List.of(BITS, SAMPLE_INTERVAL, FULL_SCALE_MIN, FULL_SCALE_MAX, ERROR_MODE, ERROR_PERCENT, DIFFERENTIAL_BIT, RECONSTRUCTION_MODE)),
        entry("quantization-error", "conversion", "Оценка погрешности квантования", "Метрики вычисляются относительно исходного сигнала на совпадающей временной сетке", "реализовано", List.of(BITS, SAMPLE_INTERVAL, FULL_SCALE_MIN, FULL_SCALE_MAX, ERROR_MODE, ERROR_PERCENT, RECONSTRUCTION_MODE)),
        entry("step-interpolation", "processing", "Интерполяция нулевого порядка", "Методичка №4; DoStepInterpolation найден в SRV.exe", "частично исследовано", List.of(STRIDE)),
        entry("linear-interpolation", "processing", "Линейная интерполяция", "Методички №1 и №4; DoLineInterpolation найден в SRV.exe", "частично исследовано", List.of(STRIDE)),
        entry("cubic-spline", "processing", "Кубический сплайн", "Естественный кубический сплайн", "реализовано", List.of(STRIDE)),
        entry("moving-average", "processing", "Скользящее среднее", "Методичка №4; SlideMiddleFiltering найден в SRV.exe", "реализовано", List.of(WINDOW, p("sampleIndex", "Начальный индекс окна", "отсчётов", 0, 9, 0, 1, true), p("useFilteredValues", "Использовать предыдущие отфильтрованные значения", "0 или 1", 0, 1, 0, 1, true))),
        entry("first-order-filter", "processing", "Цифровой ФНЧ первого порядка", "Методичка №4; DigitalFilterFiltering найден в SRV.exe", "реализовано по рекуррентной модели первого порядка", List.of(ALPHA)),
        entry("optimal-filter", "processing", "Оптимальный фильтр", "Методичка №4: y[k]=((2^n−1)y[k−1]+x[k])/2^n", "реализовано", List.of(ORDER)),
        entry("progressive-interpolation", "processing", "Прогрессивная интерполяция", "Методичка №4: последовательная интерполяция двух выборочных сеток", "реализовано", List.of(FIRST_STEP, SECOND_STEP)),
        entry("low-pass", "processing", "ФНЧ по спектральной гармонике среза", "Методичка №4: обнуление гармоник выше выбранной частоты", "реализовано как идеальный спектральный ФНЧ", List.of(CUTOFF)),
        entry("add-noise", "processing", "Добавление шума", "SignalWithHindrance найден в SRV.exe", "найдено в двоичных строках", List.of(NOISE)),
        entry("compression-zero-order", "compression", "ПИНП: интерполяция нулевого порядка", "Методичка №5: кусочно-постоянное восстановление между значимыми отсчётами", "реализовано", List.of(APERTURE, BITS, FULL_SCALE_MIN, FULL_SCALE_MAX, MAX_GAP)),
        entry("compression-linear", "compression", "ПИПП: веерная интерполяция первого порядка", "Методичка №5, рис. 2д; хорда соединяет значимые узлы в пределах апертуры", "реализовано", List.of(APERTURE, BITS, FULL_SCALE_MIN, FULL_SCALE_MAX, MAX_GAP)),
        entry("extrapolation-fixed", "compression", "ЭНП-ФА: экстраполяция с фиксированной апертурой", "Методичка №5: порог задаётся в шкале амплитуд", "реализовано", List.of(APERTURE, BITS, FULL_SCALE_MIN, FULL_SCALE_MAX, MAX_GAP)),
        entry("extrapolation-floating", "compression", "ЭНП-ПА: экстраполяция с плавающей апертурой", "Методичка №5: центр апертуры следует за последним переданным узлом; ширина остаётся фиксированной", "реализовано", List.of(APERTURE, BITS, FULL_SCALE_MIN, FULL_SCALE_MAX, MAX_GAP)),
        entry("extrapolation-linear", "compression", "ЭПП: экстраполяция первого порядка", "Методичка №5: линейное прогнозирование между переданными узлами", "реализовано", List.of(APERTURE, BITS, FULL_SCALE_MIN, FULL_SCALE_MAX, MAX_GAP))
    );

    private static Map.Entry<String, Algorithm> entry(String id, String module, String name, String evidence,
                                                       String status, List<ParameterSpec> parameters) {
        return Map.entry(id, new Algorithm(id, module, name, evidence, status, parameters));
    }
}
