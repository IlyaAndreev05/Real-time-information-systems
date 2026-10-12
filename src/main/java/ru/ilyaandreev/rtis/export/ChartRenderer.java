package ru.ilyaandreev.rtis.export;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.ui.Layer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Ellipse2D;
import java.util.List;

public final class ChartRenderer {
    public record NamedSeries(String name, List<Point> points) { }
    private ChartRenderer() { }

    public static byte[] png(LabProject project, int width, int height) throws IOException {
        if (width < 200 || width > 8192 || height < 150 || height > 8192)
            throw new IllegalArgumentException("Размер изображения вне диапазона 200–8192 × 150–8192");
        JFreeChart chart = createChart(project);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ChartUtils.writeChartAsPNG(out, chart, width, height);
            return out.toByteArray();
        }
    }

    public static JFreeChart createChart(LabProject project) {
        XYSeriesCollection data = new XYSeriesCollection();
        boolean spectrum = project.getAlgorithmId().equals("fourier") && !project.getResult().isEmpty();
        if (spectrum) add(data, "Амплитудный спектр", project.getResult());
        else {
            add(data, "Исходный сигнал", project.getInput());
            if (!project.getResult().isEmpty()) add(data, "Результат", project.getResult());
        }
        String title = spectrum ? "Амплитудный спектр" : project.getChartTitle();
        String xAxis = spectrum ? "Частота, Гц" : project.getXAxisLabel();
        JFreeChart chart = ChartFactory.createXYLineChart(title, xAxis,
            project.getYAxisLabel(), data, PlotOrientation.VERTICAL, true, true, false);
        style(chart, project.isShowGrid());
        XYPlot plot = chart.getXYPlot();
        if (!spectrum && project.getInput().isEmpty()) {
            // Keep the first manual clicks in a useful, stable working area.
            plot.getDomainAxis().setRange(0, 1);
            plot.getRangeAxis().setRange(-1, 1);
        }
        if (spectrum) {
            int last = project.getResult().size() - 1;
            int low = Math.min(last, project.getSpectrumLowHarmonic());
            int high = Math.min(last, Math.max(low, project.getSpectrumHighHarmonic()));
            double lowHz = project.getResult().get(low).x();
            double highHz = project.getResult().get(high).x();
            if (highHz > lowHz) {
                plot.getDomainAxis().setRange(lowHz, highHz);
                Color tint = project.isNotchFilterEnabled() ? new Color(220, 38, 38, 30) : new Color(37, 99, 235, 28);
                IntervalMarker selection = new IntervalMarker(lowHz, highHz, tint);
                selection.setOutlinePaint(project.isNotchFilterEnabled() ? new Color(220, 38, 38, 150) : new Color(37, 99, 235, 120));
                plot.addDomainMarker(selection, Layer.BACKGROUND);
            }
            int selected = project.getSelectedHarmonic();
            if (selected >= 0 && selected < project.getResult().size()) {
                ValueMarker marker = new ValueMarker(project.getResult().get(selected).x(), new Color(234, 88, 12, 230),
                    new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{4, 3}, 0));
                plot.addDomainMarker(marker, Layer.FOREGROUND);
            }
        } else if (!project.getInput().isEmpty()) {
            ValueMarker cursor = new ValueMarker(project.getMeasurementCursorX(), new Color(220, 38, 38, 210), new BasicStroke(1.4f));
            plot.addDomainMarker(cursor, Layer.FOREGROUND);
        }
        return chart;
    }

    public static byte[] comparisonPng(String title, String xAxis, String yAxis, List<NamedSeries> series,
                                       int width, int height) throws IOException {
        if (width < 200 || width > 8192 || height < 150 || height > 8192)
            throw new IllegalArgumentException("Размер изображения вне диапазона 200–8192 × 150–8192");
        XYSeriesCollection data = new XYSeriesCollection();
        for (NamedSeries item : series) add(data, item.name(), item.points());
        JFreeChart chart = ChartFactory.createXYLineChart(title, xAxis, yAxis, data,
            PlotOrientation.VERTICAL, true, true, false);
        style(chart, true);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ChartUtils.writeChartAsPNG(out, chart, width, height);
            return out.toByteArray();
        }
    }

    public static void savePng(LabProject project, Path destination, int width, int height) throws IOException {
        Path target = destination.toAbsolutePath();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, png(project, width, height));
    }

    public static String csv(List<Point> points) {
        StringBuilder result = new StringBuilder("x,y\n");
        for (Point p : points) result.append(Double.toString(p.x())).append(',').append(Double.toString(p.y())).append('\n');
        return result.toString();
    }

    private static void add(XYSeriesCollection data, String name, List<Point> points) {
        if (points == null || points.isEmpty()) return;
        XYSeries series = new XYSeries(name, true, false);
        points.forEach(p -> series.add(p.x(), p.y()));
        data.addSeries(series);
    }

    private static void style(JFreeChart chart, boolean showGrid) {
        Color grid = new Color(226, 232, 240), axis = new Color(148, 163, 184), text = new Color(51, 65, 85);
        chart.setBackgroundPaint(Color.WHITE);
        chart.getTitle().setFont(new Font("SansSerif", Font.BOLD, 17));
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlinePaint(new Color(226, 232, 240));
        plot.setDomainGridlinesVisible(showGrid);
        plot.setRangeGridlinesVisible(showGrid);
        plot.setDomainGridlinePaint(grid);
        plot.setRangeGridlinePaint(grid);
        plot.getDomainAxis().setAxisLinePaint(axis);
        plot.getRangeAxis().setAxisLinePaint(axis);
        plot.getDomainAxis().setTickLabelPaint(text);
        plot.getRangeAxis().setTickLabelPaint(text);
        plot.getDomainAxis().setLabelPaint(text);
        plot.getRangeAxis().setLabelPaint(text);
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        Color[] colors = {new Color(29, 78, 216), new Color(249, 115, 22), new Color(5, 150, 105)};
        for (int i = 0; i < plot.getDataset().getSeriesCount(); i++) {
            renderer.setSeriesPaint(i, colors[i % colors.length]);
            renderer.setSeriesStroke(i, new BasicStroke(2.0f));
            if (i == 0) {
                renderer.setSeriesShapesVisible(i, true);
                renderer.setSeriesShape(i, new Ellipse2D.Double(-3, -3, 6, 6));
                renderer.setSeriesFillPaint(i, new Color(220, 38, 38));
                renderer.setSeriesOutlinePaint(i, new Color(185, 28, 28));
            }
        }
        renderer.setDrawOutlines(true);
        renderer.setUseFillPaint(true);
        plot.setRenderer(renderer);
        LegendTitle legend = chart.getLegend();
        if (legend != null) {
            legend.setItemFont(new Font("SansSerif", Font.PLAIN, 11));
            legend.setBackgroundPaint(Color.WHITE);
        }
    }
}
