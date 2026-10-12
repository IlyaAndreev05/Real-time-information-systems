package ru.ilyaandreev.rtis.model;

public record Point(double x, double y) {
    public Point {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Координаты должны быть конечными числами");
        }
    }
}
