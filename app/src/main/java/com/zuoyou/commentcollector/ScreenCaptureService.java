package com.zuoyou.commentcollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Phase 2: 屏幕捕获服务 — 通过 MediaProjection 定时截取屏幕帧。
 *
 * <p>功能：
 * <ul>
 *   <li>每 3 秒截取一帧屏幕画面（480p 宽度，高度按比例）</li>
 *   <li>保存为 JPEG 到应用缓存目录</li>
 *   <li>通知 {@link ContextBuilder} 推送截图</li>
 *   <li>自动清理超过 50 帧的旧截图</li>
 *   <li>仅在抖音前台时捕获（通过 {@link #setDouyinForeground(boolean)} 控制）</li>
 * </ul>
 *
 * <p>跨 Service 通信：
 * <ul>
 *   <li>通过 Intent Action 控制：START_CAPTURE / STOP_CAPTURE</li>
 *   <li>通过 {@link #setContextBuilder(ContextBuilder)} 注入 Phase 3 管道</li>
 *   <li>通过 {@link #setDouyinForeground(boolean)} 接收前台状态</li>
 * </ul>
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCapture";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 2;

    /** JPEG 压缩质量 (0-100) */
    private static final int JPEG_QUALITY = 80;

    /** 保留最新截图数量，超出部分自动清理 */
    private static final int MAX_CAPTURES = 50;

    /** 每隔多少帧执行一次清理检查 */
    private static final int CLEANUP_EVERY_N_FRAMES = 10;

    // ── 静态字段：跨组件共享 ──

    /** 服务是否正在运行（volatile 保证跨线程可见性） */
    public static volatile boolean isRunning = false;

    /** 用于 MediaProjection 重启恢复的授权数据 */
    private static int sSavedResultCode = 0;
    private static Intent sSavedData = null;

    /** Phase 3 上下文构建器（由 DouyinCommentService 注入） */
    private static volatile ContextBuilder sContextBuilder = null;

    /** 抖音是否在前台（由 DouyinCommentService 通过无障碍事件设置） */
    private static volatile boolean sDouyinForeground = false;

    /** 静态引用，供 captureOnce() 使用 */
    private static volatile ScreenCaptureService sInstance = null;

    // ── 实例字段 ──

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private android.os.HandlerThread backgroundThread;

    private int screenWidth;
    private int screenHeight;
    private int captureWidth;
    private int captureHeight;
    private int screenDpi;

    private long mCaptureCount = 0;
    private final SimpleDateFormat fileNameFormat =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA);

    /** 预分配的 Bitmap 缓冲区（避免每帧分配 ~518KB） */
    private Bitmap reusableBitmap;

    // ── 静态方法 ──

    public static void setContextBuilder(ContextBuilder builder) {
        sContextBuilder = builder;
    }

    /**
     * 设置抖音是否在前台。由 {@link DouyinCommentService} 在无障碍事件中调用。
     * 当抖音不在前台时，截帧暂停以节省电量和保护隐私。
     */
    public static void setDouyinForeground(boolean foreground) {
        sDouyinForeground = foreground;
    }

    /**
     * 保存 MediaProjection 授权数据，用于 START_STICKY 重启恢复。
     */
    public static void saveProjectionData(int resultCode, Intent data) {
        sSavedResultCode = resultCode;
        sSavedData = data;
    }

    // ── 生命周期 ──

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startForegroundNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // START_STICKY 重启：使用保存的授权数据恢复
            if (sSavedData != null) {
                Log.d(TAG, "服务重启，使用保存的授权数据恢复捕获");
                startCapture(sSavedResultCode, sSavedData);
            } else {
                Log.w(TAG, "服务重启但无保存的授权数据，停止服务");
                stopSelf();
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        if ("START_CAPTURE".equals(action)) {
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent data = intent.getParcelableExtra("data");
            if (data != null) {
                saveProjectionData(resultCode, data);
                startCapture(resultCode, data);
            } else {
                Log.e(TAG, "START_CAPTURE 但 data 为 null");
                stopSelf();
            }
        } else if ("STOP_CAPTURE".equals(action)) {
            stopCapture();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        stopCapture();
        super.onDestroy();
    }

    // ── 捕获控制 ──

    private void startCapture(int resultCode, Intent data) {
        if (isRunning) {
            Log.d(TAG, "捕获已在运行，忽略重复请求");
            return;
        }

        startBackgroundThread();
        getScreenMetrics();

        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager 为 null");
            stopSelf();
            return;
        }

        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection 为 null — 授权可能已失效，请重新授权");
            stopSelf();
            return;
        }

        // Android 14+ 必须在 createVirtualDisplay 之前注册 callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection 已停止");
                    stopCapture();
                }
            }, backgroundHandler);
        }

        // 创建 ImageReader
        imageReader = ImageReader.newInstance(
                captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            // ImageReader 有新帧可用时的回调（由系统调用）
            // 实际截帧由外部定时器调用 captureOnce() 驱动
        }, backgroundHandler);

        // 创建虚拟显示
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ZuoYouScreenCapture",
                captureWidth, captureHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, backgroundHandler);

        // 预分配 Bitmap 缓冲区
        reusableBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888);

        isRunning = true;
        Log.d(TAG, "屏幕捕获已启动: " + captureWidth + "x" + captureHeight
                + " @ " + screenDpi + "dpi (由外部定时器驱动)");
    }

    private void stopCapture() {
        isRunning = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (reusableBitmap != null) {
            reusableBitmap.recycle();
            reusableBitmap = null;
        }

        stopBackgroundThread();
        Log.d(TAG, "屏幕捕获已停止");
    }

    // ── 截帧 ──

    /**
     * 由外部定时器（DouyinCommentService）调用，执行一次截帧。
     * 不再自行调度下一次截帧。
     */
    public static void captureOnce() {
        ScreenCaptureService instance = sInstance;
        if (instance == null || !isRunning || !sDouyinForeground) return;
        if (instance.backgroundHandler == null) return;
        instance.backgroundHandler.post(instance::captureFrame);
    }

    private void captureFrame() {
        if (!isRunning) return;

        // 仅在抖音前台时捕获
        if (!sDouyinForeground) {
            return;
        }

        // acquireLatestImage() 只保留最新帧，避免队列堆积
        if (imageReader == null) {
            return;
        }
        Image image = imageReader.acquireLatestImage();
        if (image != null) {
            try {
                Bitmap bitmap = imageToBitmap(image);
                if (bitmap != null) {
                    String filePath = saveBitmapToCache(bitmap);
                    if (filePath != null && sContextBuilder != null) {
                        sContextBuilder.pushScreenshot(filePath);
                    }
                }
            } finally {
                image.close();
            }
        }
    }

    // ── 图像处理 ──

    /**
     * 将 Image (RGBA_8888) 转为 Bitmap (ARGB_8888)。
     *
     * <p>注意：Image 的 R 和 B 通道与 Bitmap 相反，需要交换。
     * 同时处理 rowStride != width * 4 的 padding 情况。
     * 复用预分配的 {@link #reusableBitmap} 以减少 GC 压力。
     */
    private Bitmap imageToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        java.nio.ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        if (reusableBitmap == null || reusableBitmap.isRecycled()) {
            return null;
        }

        // 复用预分配的 Bitmap — 通过 setPixels 直接写入
        int[] pixels = new int[width * height];
        buffer.rewind();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * rowStride + x * pixelStride) * 4;
                if (offset + 3 >= buffer.limit()) break;
                int r = buffer.get(offset) & 0xFF;
                int g = buffer.get(offset + 1) & 0xFF;
                int b = buffer.get(offset + 2) & 0xFF;
                int a = buffer.get(offset + 3) & 0xFF;
                pixels[y * width + x] = (a << 24) | (b << 16) | (g << 8) | r;
            }
        }
        reusableBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return reusableBitmap;
    }

    /**
     * 将 Bitmap 保存为 JPEG 到缓存目录。
     *
     * @return 文件路径，失败返回 null
     */
    private String saveBitmapToCache(Bitmap bitmap) {
        String fileName = "capture_" + fileNameFormat.format(new Date()) + ".jpg";
        File file = new File(getCacheDir(), fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            boolean success = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            fos.flush();
            if (!success) {
                Log.e(TAG, "JPEG 压缩失败");
                file.delete();
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "保存截图失败", e);
            return null;
        }

        mCaptureCount++;
        if (mCaptureCount % CLEANUP_EVERY_N_FRAMES == 0) {
            cleanupOldCaptures();
        }
        return file.getAbsolutePath();
    }

    // ── 清理 ──

    /**
     * 保留最新 MAX_CAPTURES 帧截图，删除超出的旧文件。
     */
    private void cleanupOldCaptures() {
        File cacheDir = getCacheDir();
        File[] captures = cacheDir.listFiles((dir, name) -> name.startsWith("capture_"));
        if (captures == null || captures.length <= MAX_CAPTURES) return;

        // 按修改时间排序（最旧在前）
        java.util.Arrays.sort(captures, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        int toDelete = captures.length - MAX_CAPTURES;
        for (int i = 0; i < toDelete; i++) {
            if (captures[i].delete()) {
                Log.d(TAG, "清理旧截图: " + captures[i].getName());
            }
        }
    }

    // ── 屏幕参数 ──

    /**
     * 获取设备屏幕参数。
     * API 30+ 使用 WindowMetrics，旧版本使用 DisplayMetrics。
     */
    private void getScreenMetrics() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            // 回退默认值
            screenWidth = 1080;
            screenHeight = 2340;
            screenDpi = 440;
            captureWidth = Constants.CAPTURE_WIDTH_PX;
            captureHeight = (int) (screenHeight * (Constants.CAPTURE_WIDTH_PX / (double) screenWidth));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+：使用 WindowMetrics（支持折叠屏/多屏）
            android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
            android.graphics.Rect bounds = metrics.getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
            screenDpi = getResources().getConfiguration().densityDpi;
        } else {
            // API < 30：使用 DisplayMetrics
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDpi = metrics.densityDpi;
        }

        captureWidth = Constants.CAPTURE_WIDTH_PX;
        captureHeight = (int) (screenHeight * (Constants.CAPTURE_WIDTH_PX / (double) screenWidth));

        Log.d(TAG, "屏幕尺寸: " + screenWidth + "x" + screenHeight + " @ " + screenDpi + "dpi");
        Log.d(TAG, "捕获尺寸: " + captureWidth + "x" + captureHeight);
    }

    // ── 后台线程 ──

    private void startBackgroundThread() {
        backgroundThread = new android.os.HandlerThread("ScreenCaptureThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "后台线程停止等待被中断");
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ── 通知 ──

    private void startForegroundNotification() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "屏幕捕获服务",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("用于 AI 视觉分析的屏幕截图");
        channel.setShowBadge(false);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("可不 - 屏幕捕获中")
                .setContentText("正在为 AI 视觉分析截取屏幕画面")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }
}
