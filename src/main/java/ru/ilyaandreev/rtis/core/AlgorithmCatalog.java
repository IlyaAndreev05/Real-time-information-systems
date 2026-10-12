package ru.ilyaandreev.rtis.core;

import java.util.List;
import java.util.Map;

/** Algorithms currently exposed by the first Java implementation. */
public final class AlgorithmCatalog {
    private AlgorithmCatalog() { }

    public record Algorithm(String id, String moduleId, String name, String evidence, String status) { }
    public record Module(String id, String name, String lab, List<String> algorithms) { }

    public static final List<Module> MODULES = List.of(
        new Module("spectrum", "Спектральный анализ", "Лабораторная 1", List.of("fourier", "autocorrelation")),
        new Module("sampling", "Квантование", "Лабораторная 2", List.of("sample-hold", "linear-reconstruction", "quantize")),
        new Module("conversion", "АЦП и ЦАП", "Лабораторная 3", List.of("adc-dac", "quantization-error")),
        new Module("processing", "Первичная обработка", "Лабораторная 4", List.of("step-interpolation", "linear-interpolation", "cubic-spline", "moving-average", "first-order-filter", "add-noise")),
        new Module("compression", "Сжатие информации", "Лабораторная 5", List.of("compression-zero-order", "compression-linear", "extrapolation-fixed", "extrapolation-floating", "extrapolation-linear"))
    );

    public static final Map<String, Algorithm> ALGORITHMS = Map.ofEntries(
        entry("fourier", "spectrum", "Ряд Фурье и амплитудный спектр", "Методичка №1; обработчик btnShowAMFClick найден в Signal.exe", "частично исследовано"),
        entry("autocorrelation", "spectrum", "Корреляционная характеристика", "Методичка №1", "найдено в методичке"),
        entry("sample-hold", "sampling", "Ступенчатая интерполяция", "Методичка №2; menuInterpol_Step найден в SRV.exe", "частично исследовано"),
        entry("linear-reconstruction", "sampling", "Линейная интерполяция", "Методички №1–3; menuInterpol_Line найден в SRV.exe", "частично исследовано"),
        entry("quantize", "sampling", "Квантование уровня", "Методички №2–3", "найдено в методичке"),
        entry("adc-dac", "conversion", "Модель АЦП/ЦАП", "Методичка №3; функции LabBench.exe пока требуют исследования", "найдено в методичке"),
        entry("quantization-error", "conversion", "Оценка погрешности", "Методичка №3", "найдено в методичке"),
        entry("step-interpolation", "processing", "Интерполяция нулевого порядка", "Методичка №4; DoStepInterpolation найден в SRV.exe", "частично исследовано"),
        entry("linear-interpolation", "processing", "Линейная интерполяция", "Методички №1 и №4; DoLineInterpolation найден в SRV.exe", "частично исследовано"),
        entry("cubic-spline", "processing", "Кубический сплайн", "DoSplineInterpolation найден в SRV.exe", "найдено в двоичных строках"),
        entry("moving-average", "processing", "Скользящее среднее", "SlideMiddleFiltering найден в SRV.exe", "найдено в двоичных строках"),
        entry("first-order-filter", "processing", "Цифровой ФНЧ первого порядка", "Методичка №4; DigitalFilterFiltering найден в SRV.exe", "частично исследовано"),
        entry("add-noise", "processing", "Добавление шума", "SignalWithHindrance найден в SRV.exe", "найдено в двоичных строках"),
        entry("compression-zero-order", "compression", "Апертурное сжатие нулевого порядка", "Методичка №5: ПИНП", "найдено в методичке"),
        entry("compression-linear", "compression", "Апертурное сжатие первого порядка", "Методичка №5: ПИПП", "найдено в методичке"),
        entry("extrapolation-fixed", "compression", "Экстраполяция нулевого порядка, фиксированная апертура", "Методичка №5: ЭНП–ФА", "найдено в методичке"),
        entry("extrapolation-floating", "compression", "Экстраполяция нулевого порядка, плавающая апертура", "Методичка №5: ЭНП–ПА", "найдено в методичке"),
        entry("extrapolation-linear", "compression", "Экстраполяция первого порядка", "Методичка №5: ЭПП", "найдено в методичке")
    );

    private static Map.Entry<String, Algorithm> entry(String id, String module, String name, String evidence, String status) {
        return Map.entry(id, new Algorithm(id, module, name, evidence, status));
    }
}
