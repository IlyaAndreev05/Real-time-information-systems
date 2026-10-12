package ru.ilyaandreev.rtis.core;

/** User-facing numeric parameter metadata shared by the GUI and MCP catalog. */
public record ParameterSpec(String id, String name, String unit, double min, double max,
                             double defaultValue, double step, boolean integer) {
    public ParameterSpec {
        if (id == null || id.isBlank() || name == null || name.isBlank())
            throw new IllegalArgumentException("Параметру нужны идентификатор и название");
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max
            || !Double.isFinite(defaultValue) || defaultValue < min || defaultValue > max
            || !(step > 0) || !Double.isFinite(step))
            throw new IllegalArgumentException("Некорректные границы или шаг параметра " + id);
    }

    public double clamp(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Параметр " + name + " должен быть конечным числом");
        double bounded = Math.max(min, Math.min(max, value));
        return integer ? Math.rint(bounded) : bounded;
    }
}
