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
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Phase 2: 屏幕捕获服务。
 *
 * 通过 MediaProjection API 定时截取屏幕帧，保存为 JPEG 到缓存目录。
 * 捕获分辨率固定为 480p（宽 480，高根据屏幕比例计算），平衡清晰度与性能。
 *
 * ## START_STICKY 重启保护
 *
 * Android 在服务被异常杀死后会以 START_STICKY 重启（intent = null）。
 * 为了在重启后能自动恢复捕获，授权数据（resultCode + data）保存在
 * 静态字段中，重启时自动读取并重新调用 startCapture()。
 *
 * TODO:
 *   - 检查前台应用是否为抖音（需 PACKAGE_USAGE_STATS 或系统 API 支持）
 *   - 接入 Phase 3 Context Builder，直接将帧传递给 AI 处理管线
 *   - 动态分辨率适应（当前为固定 480p）
 *   - 清理旧帧缓存，防止磁盘增长
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCapture";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "screen_capture";
    private static final long CAPTURE_INTERVAL_MS = 3000;

    // 捕获分辨率（DPI 使用系统实际值，保证布局比例正确）
    private static final int CAPTURE_WIDTH = 480;

    /** volatile 保证多线程可见性（主线程写 / HandlerThread 读） */
    public static volatile boolean isRunning = false;

    /**
     * Phase 3 Context Builder 引用，由 DouyinCommentService 注入。
     */
    private static ContextBuilder sContextBuilder = null;

    /**
     * 设置 Context Builder 实例（在 DouyinCommentService.onCreate 中调用）。
     */
    public static void setContextBuilder(ContextBuilder builder) {
        sContextBuilder = builder;
    }

    /**
     * 保存 MediaProjection 授权数据，用于 START_STICKY 重启后自动恢复捕获。
     */
    private static int sSavedResultCode = -1;
    private static Intent sSavedData = null;

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;

    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private int mCaptureCount = 0;
    private int mCaptureHeight; // 根据屏幕比例计算

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "=== ScreenCaptureService 创建 ===");
        mProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: intent=" + intent
                + ", flags=" + flags + ", startId=" + startId);

        // 每次启动都必须调用 startForeground，否则 Android 会抛异常
        startForeground(NOTIFICATION_ID, buildNotification("正在初始化…"));

        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "START_CAPTURE": {
                    int resultCode = intent.getIntExtra("resultCode", -1);
                    Intent data = intent.getParcelableExtra("data");
                    // 注意：RESULT_OK == -1（Activity 常量），所以不能判断 resultCode != -1
                    if (data != null) {
                        // 保存授权数据，用于 START_STICKY 重启恢复
                        sSavedResultCode = resultCode;
                        sSavedData = data;
                        Log.d(TAG, "startCapture(" + resultCode + ", data) 被调用");
                        startCapture(resultCode, data);
                    } else {
                        Log.e(TAG, "START_CAPTURE 缺少必要的授权数据 (resultCode=" + resultCode + ")");
                    }
                    break;
                }
                case "STOP_CAPTURE":
                    stopCapture();
                    break;
            }
        } else if (sSavedData != null && !isRunning) {
            // START_STICKY 重启：intent 为 null，但从静态字段恢复捕获
            Log.i(TAG, "START_STICKY 重启，从静态字段恢复捕获");
            startCapture(sSavedResultCode, sSavedData);
        }

        return START_STICKY;
    }

    /**
     * 创建 MediaProjection + VirtualDisplay + ImageReader，启动定时捕获。
     */
    private void startCapture(int resultCode, Intent data) {
        Log.d(TAG, "startCapture() 被调用, resultCode=" + resultCode);

        // 如果已经在捕获，先停止
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
        if (mMediaProjection == null) {
            Log.e(TAG, "getMediaProjection 返回 null，无法启动捕获");
            return;
        }

        // Android 14+ 要求：在 createVirtualDisplay 之前必须先注册 callback
        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection 被系统停止");
                stopCapture();
            }
        }, null);

        // 根据屏幕比例计算捕获高度
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int realWidth = metrics.widthPixels;
        int realHeight = metrics.heightPixels;
        mCaptureHeight = (int) ((float) CAPTURE_WIDTH / realWidth * realHeight);

        // ImageReader：队列深度 2，用 acquireLatestImage 跳过堆积的旧帧
        mImageReader = ImageReader.newInstance(
                CAPTURE_WIDTH, mCaptureHeight,
                PixelFormat.RGBA_8888, 2);

        // VirtualDisplay：镜像主屏内容
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "ZuoYouScreenCapture",
                CAPTURE_WIDTH, mCaptureHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(),
                null, null);

        // 专用后台线程
        mCaptureThread = new HandlerThread("ScreenCaptureThread");
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        isRunning = true;
        mCaptureCount = 0;

        // 更新通知
        updateNotification("正在捕获屏幕…");
        scheduleNextCapture();

        Log.d(TAG, "捕获已启动：" + CAPTURE_WIDTH + "x" + mCaptureHeight
                + "，来源屏幕：" + realWidth + "x" + realHeight);
    }

    private void scheduleNextCapture() {
        if (mCaptureHandler != null && mCaptureHandler.getLooper().getThread().isAlive()) {
            mCaptureHandler.postDelayed(this::captureFrame, CAPTURE_INTERVAL_MS);
        }
    }

    /**
     * 核心捕获方法：获取最新帧 → 转为 Bitmap → 保存为 JPEG。
     */
    private void captureFrame() {
        if (!isRunning || mImageReader == null) return;

        // TODO: 检查前台应用是否为抖音，减少空耗
        // 需 PACKAGE_USAGE_STATS 权限或 AccessibilityService 支持
        // if (!isDouyinInForeground()) {
        //     scheduleNextCapture();
        //     return;
        // }

        Image image = mImageReader.acquireLatestImage();
        if (image == null) {
            Log.w(TAG, "acquireLatestImage 返回 null（无新帧），跳过");
            scheduleNextCapture();
            return;
        }

        Bitmap bitmap = imageToBitmap(image);
        image.close();

        if (bitmap == null) {
            Log.w(TAG, "imageToBitmap 返回 null，跳过");
            scheduleNextCapture();
            return;
        }

        mCaptureCount++;
        saveBitmapToCache(bitmap);
        bitmap.recycle();

        Log.d(TAG, "已捕获帧 #" + mCaptureCount + " (" + CAPTURE_WIDTH + "x" + mCaptureHeight + ")");

        scheduleNextCapture();
    }

    /**
     * 将 Image（RGBA_8888）转换为 ARGB_8888 Bitmap。
     *
     * 注意处理了：
     * - ImageReader 的 rowStride 可能 > width * pixelStride（行尾填充）
     * - RGBA → ARGB 的通道重排（R/B swap）
     */
    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();

        if (!buffer.isDirect()) {
            byte[] rgbaData = new byte[buffer.remaining()];
            buffer.get(rgbaData);
            buffer.rewind();
            return rgbaBytesToBitmap(rgbaData, width, height, pixelStride, rowStride);
        } else {
            // direct buffer：逐行读取
            byte[] row = new byte[rowStride];
            int[] argbPixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                buffer.position(y * rowStride);
                buffer.get(row, 0, rowStride);
                for (int x = 0; x < width; x++) {
                    int i = x * pixelStride;
                    int r = row[i] & 0xFF;
                    int g = row[i + 1] & 0xFF;
                    int b = row[i + 2] & 0xFF;
                    int a = row[i + 3] & 0xFF;
                    argbPixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(argbPixels, 0, width, 0, 0, width, height);
            return bitmap;
        }
    }

    /**
     * 将 row-major RGBA byte 数组转为 ARGB_8888 Bitmap。
     */
    private Bitmap rgbaBytesToBitmap(byte[] rgbaData, int width, int height,
                                     int pixelStride, int rowStride) {
        int[] argbPixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcIdx = y * rowStride + x * pixelStride;
                int dstIdx = y * width + x;
                if (srcIdx + 3 >= rgbaData.length) break;
                int r = rgbaData[srcIdx] & 0xFF;
                int g = rgbaData[srcIdx + 1] & 0xFF;
                int b = rgbaData[srcIdx + 2] & 0xFF;
                int a = rgbaData[srcIdx + 3] & 0xFF;
                argbPixels[dstIdx] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argbPixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    /**
     * 将 Bitmap 以 JPEG 80% 质量写入缓存目录。
     */
    private void saveBitmapToCache(Bitmap bitmap) {
        File cacheDir = getCacheDir();
        cleanupOldCaptures(cacheDir);

        String filename = "capture_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA)
                .format(new Date()) + ".jpg";
        File file = new File(cacheDir, filename);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            Log.d(TAG, "保存帧：" + filename + " (" + file.length() / 1024 + "KB)");

            // 通知 Context Builder（仅在抖音前台时推送有意义）
            if (sContextBuilder != null) {
                sContextBuilder.pushScreenshot(file.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "保存帧失败", e);
        }
    }

    /**
     * 缓存目录最多保留 50 个最新捕获帧，超出的删除最旧的。
     */
    private void cleanupOldCaptures(File cacheDir) {
        File[] files = cacheDir.listFiles((dir, name) -> name.startsWith("capture_") && name.endsWith(".jpg"));
        if (files == null || files.length <= 50) return;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        int toDelete = files.length - 50;
        for (int i = 0; i < toDelete; i++) {
            if (files[i].delete()) {
                Log.d(TAG, "清理旧帧：" + files[i].getName());
            }
        }
    }

    /**
     * 停止捕获并释放所有资源。不调用 stopSelf()，
     * 由系统或外部主动销毁（发送 STOP_CAPTURE）驱动。
     */
    private void stopCapture() {
        Log.d(TAG, "stopCapture() 被调用");
        isRunning = false;

        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }

        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            mCaptureThread = null;
            mCaptureHandler = null;
        }

        Log.d(TAG, "屏幕捕获已停止，本次捕获帧数：" + mCaptureCount);

        // 兼容 API 34+ 和旧版本
        if (Build.VERSION.SDK_INT >= 34) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "=== ScreenCaptureService 销毁 ===");
        stopCapture();
        // 不清除 sSavedResultCode/sSavedData，因为 START_STICKY 重启后可能还需要它们
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ───── 通知相关 ─────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "屏幕捕获",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("ScreenCaptureService 的前台通知");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("左右")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
