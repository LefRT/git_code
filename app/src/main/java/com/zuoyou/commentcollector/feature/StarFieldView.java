package com.zuoyou.commentcollector.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * 深色模式星空背景 — 背景星闪烁 + 中景亮星 + 流星。
 */
public class StarFieldView extends View {

    // ═══ 背景星（远，慢闪烁） ═══
    private static final int BG_STAR_COUNT = 100;
    private float[] bgStarX, bgStarY, bgStarSize, bgStarAlpha;
    private float[] bgStarPhase;

    // ═══ 中景亮星（偶尔闪烁更亮） ═══
    private static final int MID_STAR_COUNT = 25;
    private float[] midStarX, midStarY, midStarSize, midStarAlpha;
    private float[] midStarPhase;
    private long[] midStarFlashTime;

    // ═══ 流星 ═══
    private static final int MAX_SHOOTING_STARS = 6;
    private static final float SHOOTING_STAR_SPEED = 1200f;
    private static final float SHOOTING_STAR_LENGTH_DP = 120f;
    private final ShootingStar[] shootingStars = new ShootingStar[MAX_SHOOTING_STARS];
    private long nextShootingStarTime;

    // ═══ 画笔 ═══
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shootingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Random rng = new Random();
    private float density;
    private boolean isRunning = false;
    private long startTime;

    public StarFieldView(Context context) {
        super(context);
        init();
    }

    public StarFieldView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        shootingPaint.setStyle(Paint.Style.STROKE);
        shootingPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        initStars(w, h);
        initShootingStars();
    }

    private void initStars(int w, int h) {
        bgStarX = new float[BG_STAR_COUNT];
        bgStarY = new float[BG_STAR_COUNT];
        bgStarSize = new float[BG_STAR_COUNT];
        bgStarAlpha = new float[BG_STAR_COUNT];
        bgStarPhase = new float[BG_STAR_COUNT];

        for (int i = 0; i < BG_STAR_COUNT; i++) {
            bgStarX[i] = rng.nextFloat() * w;
            bgStarY[i] = rng.nextFloat() * h;
            bgStarSize[i] = 0.5f + rng.nextFloat() * 1.5f;
            bgStarAlpha[i] = 0.3f + rng.nextFloat() * 0.5f;
            bgStarPhase[i] = rng.nextFloat() * (float) (Math.PI * 2);
        }

        midStarX = new float[MID_STAR_COUNT];
        midStarY = new float[MID_STAR_COUNT];
        midStarSize = new float[MID_STAR_COUNT];
        midStarAlpha = new float[MID_STAR_COUNT];
        midStarPhase = new float[MID_STAR_COUNT];
        midStarFlashTime = new long[MID_STAR_COUNT];

        for (int i = 0; i < MID_STAR_COUNT; i++) {
            midStarX[i] = rng.nextFloat() * w;
            midStarY[i] = rng.nextFloat() * h;
            midStarSize[i] = 1.5f + rng.nextFloat() * 2f;
            midStarAlpha[i] = 0.6f + rng.nextFloat() * 0.4f;
            midStarPhase[i] = rng.nextFloat() * (float) (Math.PI * 2);
            midStarFlashTime[i] = System.currentTimeMillis() + 2000 + rng.nextInt(5000);
        }
    }

    private void initShootingStars() {
        for (int i = 0; i < MAX_SHOOTING_STARS; i++) {
            shootingStars[i] = new ShootingStar();
        }
        nextShootingStarTime = System.currentTimeMillis() + 500 + rng.nextInt(1000);
    }

    // ═══════════════════════════════════════════════════════
    //  动画控制
    // ═══════════════════════════════════════════════════════

    public void start() {
        if (isRunning) return;
        isRunning = true;
        startTime = System.currentTimeMillis();
        invalidate();
    }

    public void stop() {
        isRunning = false;
    }

    public boolean isRunning() {
        return isRunning;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isRunning) return;

        long now = System.currentTimeMillis();
        float elapsed = (now - startTime) / 1000f;
        int w = getWidth();
        int h = getHeight();

        drawBackgroundStars(canvas, elapsed);
        drawMidStars(canvas, elapsed);
        updateAndDrawShootingStars(canvas, w, h, now);

        invalidate();
    }

    // ═══════════════════════════════════════════════════════
    //  背景星 — 慢速呼吸闪烁
    // ═══════════════════════════════════════════════════════

    private void drawBackgroundStars(Canvas canvas, float elapsed) {
        for (int i = 0; i < BG_STAR_COUNT; i++) {
            float breath = (float) Math.sin(elapsed * 0.8 + bgStarPhase[i]);
            float alpha = bgStarAlpha[i] + breath * 0.2f;
            alpha = Math.max(0.1f, Math.min(1f, alpha));

            starPaint.setColor(Color.argb((int) (alpha * 255), 200, 215, 235));
            canvas.drawCircle(bgStarX[i], bgStarY[i], bgStarSize[i] * density, starPaint);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  中景亮星 — 偶尔闪烁更亮
    // ═══════════════════════════════════════════════════════

    private void drawMidStars(Canvas canvas, float elapsed) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < MID_STAR_COUNT; i++) {
            float alpha = midStarAlpha[i];

            if (now > midStarFlashTime[i]) {
                midStarFlashTime[i] = now + 3000 + rng.nextInt(7000);
            } else {
                long timeUntilFlash = midStarFlashTime[i] - now;
                if (timeUntilFlash < 300) {
                    float flashProgress = 1f - timeUntilFlash / 300f;
                    float flashAlpha = (float) Math.sin(flashProgress * Math.PI);
                    alpha = Math.min(1f, alpha + flashAlpha * 0.5f);
                }
            }

            float breath = (float) Math.sin(elapsed * 1.2 + midStarPhase[i]);
            alpha += breath * 0.1f;
            alpha = Math.max(0.2f, Math.min(1f, alpha));

            starPaint.setColor(Color.argb((int) (alpha * 255), 220, 235, 255));
            float sizePx = midStarSize[i] * density;
            canvas.drawCircle(midStarX[i], midStarY[i], sizePx, starPaint);

            if (alpha > 0.8f) {
                starPaint.setStrokeWidth(0.5f * density);
                float armLen = sizePx * 2f;
                canvas.drawLine(midStarX[i] - armLen, midStarY[i],
                        midStarX[i] + armLen, midStarY[i], starPaint);
                canvas.drawLine(midStarX[i], midStarY[i] - armLen,
                        midStarX[i], midStarY[i] + armLen, starPaint);
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  流星
    // ═══════════════════════════════════════════════════════

    private void updateAndDrawShootingStars(Canvas canvas, int w, int h, long now) {
        // 生成新流星（间隔 1-2.5 秒）
        if (now > nextShootingStarTime) {
            for (ShootingStar s : shootingStars) {
                if (!s.active) {
                    s.spawn(w, h, now);
                    break;
                }
            }
            nextShootingStarTime = now + 1000 + rng.nextInt(1500);
        }

        for (ShootingStar s : shootingStars) {
            if (!s.active) continue;

            float dt = (now - s.lastUpdateTime) / 1000f;
            s.lastUpdateTime = now;

            s.x += s.vx * dt * density;
            s.y += s.vy * dt * density;
            s.life -= dt;

            if (s.life <= 0 || s.x < -200 || s.x > w + 200 || s.y > h + 200) {
                s.active = false;
                continue;
            }

            float lifeRatio = Math.max(0, s.life / s.maxLife);

            // 流星头部
            int headAlpha = (int) (lifeRatio * 255);
            shootingPaint.setColor(Color.argb(headAlpha, 230, 240, 255));
            shootingPaint.setStrokeWidth(2.5f * density);
            canvas.drawCircle(s.x, s.y, 2f * density, shootingPaint);

            // 流星尾迹
            float tailLen = SHOOTING_STAR_LENGTH_DP * density;
            float tailX = s.x - s.vx * dt * density * (tailLen / (SHOOTING_STAR_SPEED * density));
            float tailY = s.y - s.vy * dt * density * (tailLen / (SHOOTING_STAR_SPEED * density));

            shootingPaint.setStrokeWidth(1.5f * density);
            int tailAlpha = (int) (lifeRatio * 120);
            shootingPaint.setColor(Color.argb(tailAlpha, 180, 200, 230));
            canvas.drawLine(s.x, s.y, tailX, tailY, shootingPaint);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  数据类
    // ═══════════════════════════════════════════════════════

    private class ShootingStar {
        float x, y, vx, vy;
        float life, maxLife;
        boolean active = false;
        long lastUpdateTime;

        void spawn(int w, int h, long now) {
            x = rng.nextFloat() * w * 0.7f;
            y = rng.nextFloat() * h * 0.3f;
            float angle = (float) (Math.PI / 6 + rng.nextFloat() * Math.PI / 4);
            float speed = 600 + rng.nextFloat() * 800;
            vx = (float) Math.cos(angle) * speed;
            vy = (float) Math.sin(angle) * speed;
            maxLife = 0.8f + rng.nextFloat() * 0.6f;
            life = maxLife;
            active = true;
            lastUpdateTime = now;
        }
    }
}
