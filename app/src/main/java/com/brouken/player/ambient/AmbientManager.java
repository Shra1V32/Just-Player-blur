package com.brouken.player.ambient;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.widget.ImageView;

import com.brouken.player.Utils;

public class AmbientManager {

    private final Activity activity;
    private final View surfaceView;
    private final ImageView ambientBackground;
    private final View topControls;
    private final View bottomControls;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    // ultra-low interval for "instant" feel (approx 30fps)
    private static final int UPDATE_INTERVAL_MS = 33; 
    
    private static final int AMBIENT_BLUR_RADIUS = 35; 
    private static final int GLASS_BLUR_RADIUS = 18;
    private static final float SCALE_DOWN_FACTOR = 0.10f; 
    private static final float SATURATION_BOOST = 1.5f;

    public AmbientManager(Activity activity, View surfaceView, ImageView ambientBackground,
                          View topControls, View bottomControls) {
        this.activity = activity;
        this.surfaceView = surfaceView;
        this.ambientBackground = ambientBackground;
        this.topControls = topControls;
        this.bottomControls = bottomControls;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        scheduleNextUpdate();
    }

    public void stop() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void scheduleNextUpdate() {
        if (!isRunning) return;
        handler.postDelayed(this::captureFrame, UPDATE_INTERVAL_MS);
    }

    private void captureFrame() {
        if (!isRunning || surfaceView == null || surfaceView.getWidth() <= 0 || surfaceView.getHeight() <= 0) {
            scheduleNextUpdate();
            return;
        }

        try {
            int width = Math.max(1, (int) (surfaceView.getWidth() * SCALE_DOWN_FACTOR));
            int height = Math.max(1, (int) (surfaceView.getHeight() * SCALE_DOWN_FACTOR));
            
            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            if (surfaceView instanceof android.view.TextureView) {
                Bitmap textureBitmap = ((android.view.TextureView) surfaceView).getBitmap(bitmap);
                if (textureBitmap != null) {
                    processFrame(textureBitmap);
                } else {
                    scheduleNextUpdate();
                }
            } else if (surfaceView instanceof android.view.SurfaceView) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Optimized PixelCopy for higher frequency
                    PixelCopy.request((android.view.SurfaceView) surfaceView, bitmap, copyResult -> {
                        if (copyResult == PixelCopy.SUCCESS) {
                            processFrame(bitmap);
                        } else {
                            scheduleNextUpdate();
                        }
                    }, handler);
                } else {
                    scheduleNextUpdate();
                }
            } else {
                scheduleNextUpdate();
            }
        } catch (Exception e) {
            scheduleNextUpdate();
        }
    }

    private void processFrame(Bitmap bitmap) {
        if (!isRunning) return;

        Bitmap vibrantBitmap = adjustSaturation(bitmap, SATURATION_BOOST);

        // YouTube Ambient Mode technique: Downscale drastically to average colors, then blur.
        // We scale to a tiny resolution (e.g. 16x16) before blurring to get a perfectly smooth glow
        // without any sharp edges or blocky artifacts, exactly like YouTube's Canvas implementation.
        Bitmap tinyBitmap = Bitmap.createScaledBitmap(vibrantBitmap, 16, 16, true);
        Bitmap ambientBitmap = BlurUtils.fastblur(tinyBitmap, 1f, 10); // Small radius on a tiny bitmap creates a massive, smooth blur
        
        // Locked Liquid Glass UI implementation (DO NOT CHANGE)
        Bitmap glassBitmap = BlurUtils.fastblur(vibrantBitmap, 1f, GLASS_BLUR_RADIUS);

        applyAmbientEffect(ambientBitmap);
        applyGlassEffect(glassBitmap);

        scheduleNextUpdate();
    }

    private Bitmap adjustSaturation(Bitmap bitmap, float saturation) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        return output;
    }

    private void applyAmbientEffect(Bitmap ambientBitmap) {
        if (ambientBackground == null || ambientBitmap == null) return;
        
        // Use a simple Drawable update for instant visual feedback
        ambientBackground.setImageDrawable(new BitmapDrawable(activity.getResources(), ambientBitmap));
        ambientBackground.setColorFilter(Color.argb(130, 0, 0, 0)); 
    }

    private void applyGlassEffect(Bitmap glassBitmap) {
        if (glassBitmap == null || bottomControls == null || topControls == null) return;

        int viewWidth = surfaceView.getWidth();
        int viewHeight = surfaceView.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;

        updateViewGlassBackground(bottomControls, glassBitmap, viewWidth, viewHeight);
        updateViewGlassBackground(topControls, glassBitmap, viewWidth, viewHeight);
    }

    private void updateViewGlassBackground(View view, Bitmap glassBitmap, int viewWidth, int viewHeight) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || view.getHeight() == 0) return;

        int[] location = new int[2];
        view.getLocationInWindow(location);
        
        int[] surfaceLocation = new int[2];
        surfaceView.getLocationInWindow(surfaceLocation);

        int relativeX = location[0] - surfaceLocation[0];
        int relativeY = location[1] - surfaceLocation[1];

        relativeX = Math.max(0, relativeX);
        relativeY = Math.max(0, relativeY);
        int cropWidth = Math.min(view.getWidth(), viewWidth - relativeX);
        int cropHeight = Math.min(view.getHeight(), viewHeight - relativeY);

        if (cropWidth <= 0 || cropHeight <= 0) return;

        float scaleX = (float) glassBitmap.getWidth() / viewWidth;
        float scaleY = (float) glassBitmap.getHeight() / viewHeight;

        int bX = Math.max(0, (int) (relativeX * scaleX));
        int bY = Math.max(0, (int) (relativeY * scaleY));
        int bW = Math.max(1, Math.min((int) (cropWidth * scaleX), glassBitmap.getWidth() - bX));
        int bH = Math.max(1, Math.min((int) (cropHeight * scaleY), glassBitmap.getHeight() - bY));

        try {
            Bitmap cropped = Bitmap.createBitmap(glassBitmap, bX, bY, bW, bH);
            
            int cornerRadius = Utils.dpToPx(16);
            Bitmap result = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            RectF rectF = new RectF(0, 0, view.getWidth(), view.getHeight());
            
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(Bitmap.createScaledBitmap(cropped, view.getWidth(), view.getHeight(), true), 0, 0, paint);
            
            paint.setXfermode(null);
            paint.setColor(Color.argb(180, 10, 10, 10)); // Deep premium frosting
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
            
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Utils.dpToPx(1));
            paint.setColor(Color.argb(50, 255, 255, 255));
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);

            view.setBackground(new BitmapDrawable(activity.getResources(), result));
            
        } catch (Exception e) {
            // silent fail
        }
    }
}