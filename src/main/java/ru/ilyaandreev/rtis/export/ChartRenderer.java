package ru.ilyaandreev.rtis.export;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.LegendTitle;
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
import java.util.List;

public final class ChartRenderer {
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
        return chart;
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
        }
        plot.setRenderer(renderer);
        LegendTitle legend = chart.getLegend();
        if (legend != null) {
            legend.setItemFont(new Font("SansSerif", Font.PLAIN, 11));
            legend.setBackgroundPaint(Color.WHITE);
        }
    }
}
