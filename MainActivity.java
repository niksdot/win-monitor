package com.niksdot.winmonitor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DashboardView dashboard;
    private Monitor monitor;
    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (dashboard == null || monitor == null) return;
            dashboard.setSample(monitor.sample());
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(13, 17, 23));
        getWindow().setNavigationBarColor(Color.rgb(13, 17, 23));
        monitor = new Monitor(this);
        dashboard = new DashboardView(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(dashboard);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(sampler);
        sampler.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(sampler);
        super.onPause();
    }

    private static final class Sample {
        float cpu;
        float gpu;
        float ram;
        long usedRam;
        long totalRam;
        String gpuDetail;
        boolean gpuAvailable;
    }

    private static final class CpuReader {
        private long prevIdle = -1L;
        private long prevTotal = -1L;

        float read() {
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
                String line = br.readLine();
                if (line == null || !line.startsWith("cpu ")) return 0f;
                String[] p = line.trim().split("\\s+");
                long user = Long.parseLong(p[1]);
                long nice = Long.parseLong(p[2]);
                long system = Long.parseLong(p[3]);
                long idle = Long.parseLong(p[4]);
                long iowait = p.length > 5 ? Long.parseLong(p[5]) : 0L;
                long irq = p.length > 6 ? Long.parseLong(p[6]) : 0L;
                long softirq = p.length > 7 ? Long.parseLong(p[7]) : 0L;
                long steal = p.length > 8 ? Long.parseLong(p[8]) : 0L;
                long total = user + nice + system + idle + iowait + irq + softirq + steal;
                if (prevTotal < 0L) {
                    prevTotal = total;
                    prevIdle = idle + iowait;
                    return 0f;
                }
                long totalDelta = total - prevTotal;
                long idleDelta = idle + iowait - prevIdle;
                prevTotal = total;
                prevIdle = idle + iowait;
                if (totalDelta <= 0) return 0f;
                return clamp(100f * (1f - (float) idleDelta / totalDelta));
            } catch (IOException | RuntimeException ignored) {
                return 0f;
            }
        }
    }

    private static final class GpuReader {
        private long prevBusy = -1L;
        private long prevTotal = -1L;
        private final String busyFile;
        private final String totalFile;

        GpuReader() {
            String[] direct = {
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_busy_percentage"
            };
            String found = null;
            for (String path : direct) if (new File(path).canRead()) { found = path; break; }
            if (found != null) {
                busyFile = found;
                totalFile = null;
                return;
            }
            String[] candidates = {
                "/sys/class/devfreq/gpufreq/load",
                "/sys/class/devfreq/gpu/load",
                "/sys/class/devfreq/mali0/load",
                "/sys/class/misc/mali0/device/utilization"
            };
            found = null;
            for (String path : candidates) if (new File(path).canRead()) { found = path; break; }
            if (found != null) {
                busyFile = found;
                totalFile = null;
                return;
            }
            busyFile = findBusyTimeFile();
            totalFile = busyFile == null ? null : busyFile.replace("busy_time", "total_time");
        }

        Result read() {
            if (busyFile == null) return new Result(0f, false, "GPU load unavailable on this device");
            try {
                String raw = readText(busyFile).trim();
                if (totalFile == null && raw.matches("\\d+")) {
                    float v = Float.parseFloat(raw);
                    if (v <= 100f) return new Result(clamp(v), true, "Kernel GPU utilization");
                }
                if (totalFile != null) {
                    long busy = Long.parseLong(raw.split("\\s+")[0]);
                    long total = Long.parseLong(readText(totalFile).trim().split("\\s+")[0]);
                    if (prevTotal >= 0 && total > prevTotal && busy >= prevBusy) {
                        float v = 100f * (busy - prevBusy) / (float) (total - prevTotal);
                        prevBusy = busy;
                        prevTotal = total;
                        return new Result(clamp(v), true, "GPU devfreq utilization");
                    }
                    prevBusy = busy;
                    prevTotal = total;
                    return new Result(0f, true, "GPU devfreq utilization");
                }
                return new Result(0f, false, "GPU load format unsupported");
            } catch (IOException | RuntimeException ignored) {
                return new Result(0f, false, "GPU load unavailable");
            }
        }

        private static String findBusyTimeFile() {
            File root = new File("/sys/class/devfreq");
            File[] dirs = root.listFiles();
            if (dirs == null) return null;
            for (File dir : dirs) {
                String n = dir.getName().toLowerCase(Locale.US);
                if (!(n.contains("gpu") || n.contains("mali") || n.contains("kgsl") || n.contains("3d"))) continue;
                File busy = new File(dir, "busy_time");
                File total = new File(dir, "total_time");
                if (busy.canRead() && total.canRead()) return busy.getAbsolutePath();
            }
            return null;
        }

        private static final class Result {
            final float value;
            final boolean available;
            final String detail;
            Result(float value, boolean available, String detail) {
                this.value = value; this.available = available; this.detail = detail;
            }
        }
    }

    private static final class Monitor {
        private final android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
        private final android.app.ActivityManager am;
        private final CpuReader cpu = new CpuReader();
        private final GpuReader gpu = new GpuReader();

        Monitor(Context context) {
            am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        }

        Sample sample() {
            Sample s = new Sample();
            s.cpu = cpu.read();
            am.getMemoryInfo(memInfo);
            s.totalRam = Math.max(1L, memInfo.totalMem);
            s.usedRam = Math.max(0L, s.totalRam - memInfo.availMem);
            s.ram = clamp(100f * (float) s.usedRam / s.totalRam);
            GpuReader.Result g = gpu.read();
            s.gpu = g.value;
            s.gpuAvailable = g.available;
            s.gpuDetail = g.detail;
            return s;
        }
    }

    private final class DashboardView extends View {
        private static final int BG = Color.rgb(13, 17, 23);
        private static final int SURFACE = Color.rgb(22, 27, 34);
        private static final int TEXT = Color.rgb(230, 237, 243);
        private static final int MUTED = Color.rgb(139, 148, 158);
        private static final int CPU = Color.rgb(88, 166, 255);
        private static final int GPU = Color.rgb(63, 185, 80);
        private static final int RAM = Color.rgb(210, 153, 34);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Float> cpu = new ArrayList<>();
        private final ArrayList<Float> gpu = new ArrayList<>();
        private final ArrayList<Float> ram = new ArrayList<>();
        private Sample current = new Sample();
        private float density;

        DashboardView(Context c) {
            super(c);
            density = getResources().getDisplayMetrics().density;
            setBackgroundColor(BG);
            paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        }

        void setSample(Sample s) {
            current = s;
            add(cpu, s.cpu); add(gpu, s.gpu); add(ram, s.ram);
            invalidate();
        }

        private void add(ArrayList<Float> a, float v) {
            if (a.size() == 60) a.remove(0);
            a.add(clamp(v));
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int height = (int) dp(980);
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float pad = dp(16);
            float y = dp(12);
            paint.setColor(TEXT);
            paint.setTextSize(dp(28));
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.drawText("Win Monitor", pad, y + dp(26), paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            paint.setColor(MUTED);
            paint.setTextSize(dp(12));
            c.drawText("Real-time system telemetry", pad, y + dp(48), paint);
            y += dp(64);

            float cardH = dp(88);
            float gap = dp(10);
            float cardW = (w - pad * 2 - gap * 2) / 3f;
            drawMetricCard(c, pad, y, cardW, cardH, "CPU", current.cpu, "%", CPU);
            drawMetricCard(c, pad + cardW + gap, y, cardW, cardH, "GPU", current.gpu, "%", GPU);
            drawMetricCard(c, pad + (cardW + gap) * 2, y, cardW, cardH, "RAM", current.ram, "%", RAM);
            y += cardH + dp(12);

            drawGraph(c, pad, y, w - pad * 2, dp(150), "CPU", CPU, cpu);
            y += dp(162);
            drawGraph(c, pad, y, w - pad * 2, dp(150), "GPU", GPU, gpu);
            y += dp(162);
            drawGraph(c, pad, y, w - pad * 2, dp(150), "RAM", RAM, ram);
            y += dp(170);

            paint.setColor(MUTED);
            paint.setTextSize(dp(12));
            String memory = formatBytes(current.usedRam) + " / " + formatBytes(current.totalRam);
            c.drawText("RAM " + memory, pad, y, paint);
            c.drawText(current.gpuAvailable ? current.gpuDetail : "GPU " + current.gpuDetail, pad, y + dp(20), paint);
            c.drawText("Samples: " + cpu.size() + " / 60", pad, y + dp(40), paint);
        }

        private void drawMetricCard(Canvas c, float x, float y, float w, float h, String label, float value, String unit, int accent) {
            paint.setColor(SURFACE);
            c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(14), dp(14), paint);
            paint.setColor(accent);
            c.drawRoundRect(new RectF(x, y, x + dp(5), y + h), dp(3), dp(3), paint);
            paint.setColor(MUTED);
            paint.setTextSize(dp(12));
            c.drawText(label, x + dp(14), y + dp(24), paint);
            paint.setColor(TEXT);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(24));
            c.drawText(String.format(Locale.US, "%.0f%s", value, unit), x + dp(14), y + dp(58), paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        private void drawGraph(Canvas c, float x, float y, float w, float h, String title, int accent, ArrayList<Float> data) {
            paint.setColor(SURFACE);
            c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(14), dp(14), paint);
            paint.setColor(MUTED);
            paint.setTextSize(dp(12));
            c.drawText(title + " · 60s", x + dp(14), y + dp(22), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(48, 54, 61));
            for (int i = 1; i < 4; i++) {
                float gy = y + dp(32) + (h - dp(48)) * i / 4f;
                c.drawLine(x + dp(12), gy, x + w - dp(12), gy, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            Path p = new Path();
            if (!data.isEmpty()) {
                float left = x + dp(12);
                float top = y + dp(32);
                float plotW = w - dp(24);
                float plotH = h - dp(48);
                for (int i = 0; i < data.size(); i++) {
                    float px = left + plotW * (data.size() == 1 ? 0f : (float) i / 59f);
                    float py = top + plotH * (1f - data.get(i) / 100f);
                    if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
                }
                paint.setColor(accent);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setStrokeCap(Paint.Cap.ROUND);
                c.drawPath(p, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(MUTED);
            paint.setTextSize(dp(10));
            c.drawText("100%", x + w - dp(39), y + dp(44), paint);
            c.drawText("0%", x + w - dp(27), y + h - dp(13), paint);
        }

        private float dp(float v) { return v * density; }
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(100f, v)); }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (value >= 1024 && i < units.length - 1) { value /= 1024.0; i++; }
        return String.format(Locale.US, "%.1f %s", value, units[i]);
    }

    private static String readText(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) b.append(line).append('\n');
            return b.toString();
        }
    }
}
