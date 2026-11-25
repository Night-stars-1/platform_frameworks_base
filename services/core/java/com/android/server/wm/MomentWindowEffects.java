/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

import android.content.res.Configuration;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.util.Slog;
import android.view.SurfaceControl;

/** A shared-buffer, eight-slice soft surround: no app capture or per-frame rasterization. */
final class MomentWindowEffects {
    private final Task mTask;
    private final SurfaceControl[] mTiles = new SurfaceControl[8];
    private HardwareBuffer mTexture;
    private int mCorner;
    private float mRadius;
    private float mPadding;
    private boolean mDark;
    private final Rect mCrop = new Rect();

    MomentWindowEffects(Task task) {
        mTask = task;
    }

    void apply(SurfaceControl.Transaction t, float left, float top, float width, float height,
            float radius, float alpha, float density) {
        final SurfaceControl parent = mTask.getParentSurfaceControl();
        if (parent == null || !mTask.isVisibleRequested() || radius < 1f || alpha <= 0f) {
            hide(t);
            return;
        }
        final boolean dark = (mTask.mWmService.mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        try {
            if (mTexture == null || dark != mDark || mRadius != 28f * density) {
                createTexture(density, dark);
            }
            final float factor = Math.min(radius / mRadius,
                    Math.min(width, height) / (2f * mRadius));
            final float pad = mPadding * factor;
            final float corner = mCorner * factor;
            final float[] xs = {left - pad, left - pad + corner,
                    left + width + pad - corner, left + width + pad};
            final float[] ys = {top - pad, top - pad + corner,
                    top + height + pad - corner, top + height + pad};
            int index = 0;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    if (row == 1 && col == 1) continue;
                    SurfaceControl tile = mTiles[index];
                    if (tile == null) {
                        tile = mTask.mWmService.makeSurfaceBuilder().setParent(parent)
                                .setName("MomentSurround#" + mTask.mTaskId + ":" + index)
                                .setBLASTLayer().setCallsite("MomentWindowEffects").build();
                        mTiles[index] = tile;
                    }
                    final int x = col == 0 ? 0 : col == 1 ? mCorner : mCorner + 1;
                    final int y = row == 0 ? 0 : row == 1 ? mCorner : mCorner + 1;
                    final int w = col == 1 ? 1 : mCorner;
                    final int h = row == 1 ? 1 : mCorner;
                    final float sx = (xs[col + 1] - xs[col]) / w;
                    final float sy = (ys[row + 1] - ys[row]) / h;
                    mCrop.set(x, y, x + w, y + h);
                    t.reparent(tile, parent).setBuffer(tile, mTexture)
                            .setRelativeLayer(tile, mTask.mSurfaceControl, -1)
                            .setWindowCrop(tile, mCrop).setScale(tile, sx, sy)
                            .setPosition(tile, xs[col] - x * sx, ys[row] - y * sy)
                            .setAlpha(tile, alpha).show(tile);
                    index++;
                }
            }
        } catch (RuntimeException e) {
            Slog.w("Moment", "Unable to render window surround", e);
            hide(t);
        }
    }

    private void createTexture(float density, boolean dark) {
        mRadius = 28f * density;
        mPadding = (float) Math.ceil(24f * density);
        mCorner = (int) Math.ceil(mRadius + mPadding);
        final int size = mCorner * 2 + 1;
        final GraphicBuffer buffer = GraphicBuffer.create(size, size, PixelFormat.RGBA_8888,
                GraphicBuffer.USAGE_SW_WRITE_OFTEN | GraphicBuffer.USAGE_HW_TEXTURE
                        | GraphicBuffer.USAGE_HW_COMPOSER);
        if (buffer == null) throw new IllegalStateException("No surround buffer");
        try {
            final Canvas canvas = buffer.lockCanvas();
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                final RectF shape = new RectF(mPadding, mPadding,
                        size - mPadding, size - mPadding);
                final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.BLACK);
                paint.setShadowLayer(12f * density, 0, 3f * density,
                        dark ? 0x20000000 : 0x38000000);
                canvas.drawRoundRect(shape, mRadius, mRadius, paint);
                paint.setShadowLayer(3f * density, 0, density,
                        dark ? 0x28000000 : 0x40000000);
                canvas.drawRoundRect(shape, mRadius, mRadius, paint);
                paint.clearShadowLayer();
                if (dark) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2f * density);
                    paint.setColor(0x38FFFFFF);
                    paint.setMaskFilter(new BlurMaskFilter(1.5f * density,
                            BlurMaskFilter.Blur.NORMAL));
                    canvas.drawRoundRect(shape, mRadius, mRadius, paint);
                    paint.setMaskFilter(null);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                canvas.drawRoundRect(shape, mRadius, mRadius, paint);
            } finally {
                buffer.unlockCanvasAndPost(canvas);
            }
            final HardwareBuffer texture = HardwareBuffer.createFromGraphicBuffer(buffer);
            if (mTexture != null) mTexture.close();
            mTexture = texture;
            mDark = dark;
        } finally {
            buffer.destroy();
        }
    }

    void hide(SurfaceControl.Transaction t) {
        for (SurfaceControl tile : mTiles) {
            if (tile != null && tile.isValid()) t.hide(tile);
        }
    }

    void destroy(SurfaceControl.Transaction t) {
        for (int i = 0; i < mTiles.length; i++) {
            if (mTiles[i] == null) continue;
            if (mTiles[i].isValid()) t.remove(mTiles[i]);
            mTiles[i].release();
            mTiles[i] = null;
        }
        if (mTexture != null) {
            mTexture.close();
            mTexture = null;
        }
    }
}
