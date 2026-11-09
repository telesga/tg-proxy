/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Centered popup in the Telezhka style: an icon (or the animated update hero), a label, a title,
 * a short list of points, a main orange button and a quiet secondary one.
 * The update popup also shows a progress bar in place of the buttons while downloading.
 */
public class TelezhkaPopup extends Dialog {

    private static final int PANEL = 0xFF1C1F28;
    private static final int TEXT = 0xFFF2F3F6;
    private static final int TEXT_2 = 0xFFC9CDD6;
    private static final int MUTE = 0xFF8E95A4;
    private static final int SIGNAL = 0xFFFF6B3D;

    private final FrameLayout container;
    private final LinearLayout card;
    private final LinearLayout body;
    private final TextView labelView;
    private final TextView titleView;
    private final LinearLayout items;
    private final TextView mainButton;
    private final TextView secondaryButton;
    private final LinearLayout buttons;
    private final LinearLayout progressBox;
    private final ProgressLine progressLine;
    private final TextView progressLeft;
    private final TextView progressRight;
    private View iconView;
    private int itemCount;

    public TelezhkaPopup(Context context) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        container = new FrameLayout(context);
        container.setOnClickListener(v -> {
            if (isCancelableOutside) {
                dismiss();
            }
        });

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(30), PANEL));
        card.setClipToOutline(true);
        card.setClickable(true);
        container.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 22, 0, 22, 0));

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(24), dp(22), dp(14));
        card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        labelView.setTypeface(AndroidUtilities.bold());
        labelView.setLetterSpacing(0.12f);
        labelView.setAllCaps(true);
        labelView.setTextColor(SIGNAL);
        labelView.setGravity(Gravity.CENTER);
        labelView.setVisibility(View.GONE);
        body.addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.display());
        titleView.setTextColor(TEXT);
        titleView.setGravity(Gravity.CENTER);
        body.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        body.addView(items, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        body.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        mainButton = new TextView(context);
        mainButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        mainButton.setTypeface(AndroidUtilities.bold());
        mainButton.setTextColor(Color.WHITE);
        mainButton.setGravity(Gravity.CENTER);
        mainButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), SIGNAL, 0x33FFFFFF));
        buttons.addView(mainButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        secondaryButton = new TextView(context);
        secondaryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        secondaryButton.setTypeface(AndroidUtilities.bold());
        secondaryButton.setTextColor(MUTE);
        secondaryButton.setGravity(Gravity.CENTER);
        secondaryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), 0, 0x14FFFFFF));
        secondaryButton.setVisibility(View.GONE);
        buttons.addView(secondaryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 6, 0, 0));

        progressBox = new LinearLayout(context);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setVisibility(View.GONE);
        body.addView(progressBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 8));
        progressLine = new ProgressLine(context);
        progressBox.addView(progressLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        LinearLayout progressTexts = new LinearLayout(context);
        progressTexts.setOrientation(LinearLayout.HORIZONTAL);
        progressBox.addView(progressTexts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        progressLeft = smallText(context);
        progressTexts.addView(progressLeft, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        progressRight = smallText(context);
        progressRight.setGravity(Gravity.RIGHT);
        progressTexts.addView(progressRight, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
    }

    private boolean isCancelableOutside = true;

    private static TextView smallText(Context context) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(MUTE);
        return view;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(container);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.6f);
            if (hasInput) {
                // the card moves up above the keyboard
                window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
        }
    }

    @Override
    public void show() {
        super.show();
        card.setAlpha(0f);
        card.setScaleX(0.9f);
        card.setScaleY(0.9f);
        card.setTranslationY(dp(24));
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
    }

    public TelezhkaPopup setCancelableOutside(boolean cancelable) {
        isCancelableOutside = cancelable;
        setCancelable(cancelable);
        return this;
    }

    /** Small orange-tinted tile with an icon above the title. */
    public TelezhkaPopup setIcon(int drawableRes) {
        FrameLayout tile = new FrameLayout(getContext());
        tile.setBackground(Theme.createRoundRectDrawable(dp(20), 0x24FF6B3D));
        android.widget.ImageView image = new android.widget.ImageView(getContext());
        image.setImageResource(drawableRes);
        image.setColorFilter(new android.graphics.PorterDuffColorFilter(SIGNAL, android.graphics.PorterDuff.Mode.SRC_IN));
        image.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        tile.addView(image, LayoutHelper.createFrame(30, 30, Gravity.CENTER));
        setTopView(tile, LayoutHelper.createLinear(60, 60, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));
        return this;
    }

    /** The big update header: dot grid, orange glow and a tile with a dropping arrow. */
    public TelezhkaPopup setUpdateHero(CharSequence badge) {
        if (iconView != null) {
            ((LinearLayout) iconView.getParent()).removeView(iconView);
        }
        FrameLayout hero = new FrameLayout(getContext());
        hero.addView(new UpdateHeroView(getContext()), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (!TextUtils.isEmpty(badge)) {
            TextView badgeView = new TextView(getContext());
            badgeView.setText(badge);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setTextColor(TEXT);
            badgeView.setPadding(dp(10), dp(4), dp(10), dp(4));
            badgeView.setBackground(Theme.createRoundRectDrawable(dp(13), 0x1AFFFFFF));
            hero.addView(badgeView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 14, 14, 0));
        }
        iconView = hero;
        card.addView(hero, 0, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 150));
        body.setPadding(dp(22), dp(20), dp(22), dp(14));
        return this;
    }

    private void setTopView(View view, LinearLayout.LayoutParams params) {
        if (iconView != null) {
            ((LinearLayout) iconView.getParent()).removeView(iconView);
        }
        iconView = view;
        body.addView(view, 0, params);
    }

    public TelezhkaPopup setLabel(CharSequence label) {
        labelView.setText(label);
        labelView.setVisibility(TextUtils.isEmpty(label) ? View.GONE : View.VISIBLE);
        return this;
    }

    public TelezhkaPopup setTitleText(CharSequence title) {
        titleView.setText(title);
        return this;
    }

    /** A point with its number in a small tile; text may hold **bold** parts. */
    public TelezhkaPopup addNumberedItem(CharSequence text) {
        itemCount++;
        return addItem(String.valueOf(itemCount), text);
    }

    /** A point marked with an orange dot. */
    public TelezhkaPopup addDotItem(CharSequence text) {
        return addItem(null, text);
    }

    private TelezhkaPopup addItem(String number, CharSequence text) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (number != null) {
            TextView badge = new TextView(getContext());
            badge.setText(number);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setTextColor(SIGNAL);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Theme.createRoundRectDrawable(dp(8), 0x12FFFFFF));
            row.addView(badge, LayoutHelper.createLinear(22, 22, 0, 0, 10, 0));
        } else {
            View dot = new View(getContext());
            dot.setBackground(Theme.createCircleDrawable(dp(6), SIGNAL));
            row.addView(dot, LayoutHelper.createLinear(6, 6, Gravity.TOP, 4, 8, 14, 0));
        }
        TextView textView = new TextView(getContext());
        textView.setText(AndroidUtilities.replaceTags(text.toString()));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTextColor(TEXT_2);
        textView.setLineSpacing(dp(2), 1f);
        row.addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        items.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        return this;
    }

    /** A text field under the points, filled with {@code text}; the keyboard opens with it. */
    public EditTextBoldCursor addInput(CharSequence text, CharSequence hint) {
        EditTextBoldCursor input = new EditTextBoldCursor(getContext());
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(TEXT);
        input.setHintText(hint);
        input.setHintColor(MUTE);
        input.setCursorColor(SIGNAL);
        input.setCursorSize(dp(20));
        input.setCursorWidth(1.5f);
        input.setLineSpacing(dp(2), 1f);
        input.setMinLines(2);
        input.setMaxLines(8);
        input.setVerticalScrollBarEnabled(true);
        input.setGravity(Gravity.TOP | Gravity.LEFT);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setBackground(Theme.createRoundRectDrawable(dp(16), 0x12FFFFFF));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (text != null) {
            input.setText(text);
            input.setSelection(input.length());
        }
        items.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        hasInput = true;
        setOnShowListener(d -> input.postDelayed(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 250));
        return input;
    }

    private boolean hasInput;

    public TelezhkaPopup setMainButton(CharSequence text, View.OnClickListener listener) {
        mainButton.setText(text);
        mainButton.setOnClickListener(listener);
        return this;
    }

    public TelezhkaPopup setSecondaryButton(CharSequence text, View.OnClickListener listener) {
        secondaryButton.setText(text);
        secondaryButton.setOnClickListener(listener);
        secondaryButton.setVisibility(View.VISIBLE);
        return this;
    }

    /** Switches between the buttons and the progress bar. */
    public void showProgress(boolean show) {
        buttons.setVisibility(show ? View.GONE : View.VISIBLE);
        progressBox.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void setProgress(float value, CharSequence left, CharSequence right) {
        progressLine.setProgress(value);
        progressLeft.setText(left);
        progressRight.setText(right);
    }

    private static class ProgressLine extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progress;

        ProgressLine(Context context) {
            super(context);
        }

        void setProgress(float value) {
            progress = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            paint.setColor(0x1AFFFFFF);
            canvas.drawRoundRect(rect, r, r, paint);
            rect.right = Math.max(getHeight(), getWidth() * progress);
            paint.setColor(SIGNAL);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }

    private static class UpdateHeroView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final long start = SystemClock.elapsedRealtime();

        UpdateHeroView(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawColor(0xFF14161C);
            paint.setShader(new RadialGradient(w / 2f, h, Math.max(1, h * 0.95f), 0x73FF6B3D, 0x00FF6B3D, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            // dot grid, fading out from the center
            float step = dp(16);
            for (float y = step / 2; y < h; y += step) {
                for (float x = step / 2; x < w; x += step) {
                    float dx = (x - w / 2f) / (w * 0.6f), dy = (y - h * 0.7f) / (h * 0.8f);
                    float k = 1f - Math.min(1f, dx * dx + dy * dy);
                    if (k <= 0) {
                        continue;
                    }
                    paint.setColor(Color.argb((int) (41 * k), 255, 255, 255));
                    canvas.drawCircle(x, y, dp(1.2f), paint);
                }
            }

            float t = (SystemClock.elapsedRealtime() - start) / 1000f;
            float bob = (float) Math.sin(t * Math.PI * 2 / 2.4f) * dp(2.5f) - dp(2.5f);
            float size = dp(84);
            float cx = w / 2f, cy = h / 2f + bob;
            rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            paint.setColor(0x55FF6B3D);
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(dp(16), android.graphics.BlurMaskFilter.Blur.NORMAL));
            canvas.drawRoundRect(rect.left + dp(6), rect.top + dp(14), rect.right - dp(6), rect.bottom + dp(10), dp(26), dp(26), paint);
            paint.setMaskFilter(null);
            paint.setColor(SIGNAL);
            canvas.drawRoundRect(rect, dp(26), dp(26), paint);

            // the arrow drops in and fades, the base line stays
            float p = (t % 1.6f) / 1.6f;
            float shift, alpha;
            if (p < 0.3f) {
                float k = p / 0.3f;
                shift = -dp(6) * (1 - k);
                alpha = k;
            } else if (p < 0.7f) {
                shift = 0;
                alpha = 1;
            } else {
                float k = (p - 0.7f) / 0.3f;
                shift = dp(4) * k;
                alpha = 1 - k;
            }
            float u = size / 24f;
            stroke.setStrokeWidth(2.4f * u);
            stroke.setAlpha((int) (255 * alpha));
            path.reset();
            float ox = cx - 12 * u, oy = cy - 12 * u + shift;
            path.moveTo(ox + 12 * u, oy + 4 * u);
            path.lineTo(ox + 12 * u, oy + 14 * u);
            path.moveTo(ox + 7.5f * u, oy + 9.5f * u);
            path.lineTo(ox + 12 * u, oy + 14 * u);
            path.lineTo(ox + 16.5f * u, oy + 9.5f * u);
            canvas.drawPath(path, stroke);
            stroke.setAlpha(255);
            canvas.drawLine(cx - 7 * u, cy + 7 * u, cx + 7 * u, cy + 7 * u, stroke);
            invalidate();
        }
    }
}
