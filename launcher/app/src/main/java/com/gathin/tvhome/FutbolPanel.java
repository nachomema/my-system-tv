package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Draws the football panel under the header's weather line: one column per team, each with the team's name and
 * up to two matches (the competition and the state over the score). Plain text views, rebuilt when the data
 * changes (at most once a minute), never animated.
 */
final class FutbolPanel {

    private static final int LIVE_RED = 0xFFFF3B2F;
    private static final int CELESTE = 0xFF75AADB;

    private final LinearLayout panel;
    private final Typeface medium;
    private final Typeface light;
    private final float density;
    private final boolean is24h;
    /** What is on screen, so an unchanged minute rebuilds nothing. */
    private String shown = "";

    FutbolPanel(LinearLayout panel, Typeface medium, Typeface light) {
        this.panel = panel;
        this.medium = medium;
        this.light = light;
        Context ctx = panel.getContext();
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.is24h = android.text.format.DateFormat.is24HourFormat(ctx);
    }

    void hide() {
        panel.setVisibility(View.GONE);
        panel.removeAllViews();
        shown = "";
    }

    void render(List<Futbol.Block> blocks) {
        if (blocks.isEmpty()) {
            hide();
            return;
        }
        String key = signature(blocks);
        if (key.equals(shown) && panel.getVisibility() == View.VISIBLE) return;
        shown = key;
        panel.removeAllViews();
        for (int i = 0; i < blocks.size(); i++) {
            View column = column(blocks.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(22));
            panel.addView(column, lp);
        }
        panel.setVisibility(View.VISIBLE);
    }

    private View column(Futbol.Block block) {
        Context ctx = panel.getContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        // The team's name after a short bar: celeste for a national team.
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(ctx);
        bar.setBackgroundColor(block.team.national ? CELESTE : 0xCCFFFFFF);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), dp(12));
        barLp.setMarginEnd(dp(8));
        head.addView(bar, barLp);
        TextView name = text(ctx, block.team.national
                ? "Selecciones nacionales · " + block.team.name : block.team.name, 12, 0xD9FFFFFF, medium);
        name.setAllCaps(true);
        name.setLetterSpacing(0.16f);
        head.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(head);

        if (block.matches.isEmpty()) {
            TextView none = text(ctx, "Sin partidos por ahora", 14, 0x99FFFFFF, light);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            col.addView(none, lp);
            return col;
        }
        for (Futbol.Match m : block.matches) {
            LinearLayout.LayoutParams compLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            compLp.topMargin = dp(9);
            col.addView(text(ctx, kicker(m), 10, 0xA6FFFFFF, medium, 0.12f), compLp);
            LinearLayout.LayoutParams scoreLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            scoreLp.topMargin = dp(2);
            col.addView(text(ctx, scoreLine(m), 16, 0xFFFFFFFF, light), scoreLp);
        }
        return col;
    }

    /** "LIGA PROFESIONAL · FINAL", "COPA LIBERTADORES · ● EN VIVO 71'", "MUNDIAL · HOY 21:00". */
    private CharSequence kicker(Futbol.Match m) {
        SpannableStringBuilder s = new SpannableStringBuilder(Futbol.competition(m).toUpperCase(Locale.ROOT));
        s.append("  ·  ");
        String status = Futbol.status(m);
        if (m.live()) {
            int start = s.length();
            s.append("● EN VIVO ").append(status == null ? "" : status.toUpperCase(Locale.ROOT));
            s.setSpan(new ForegroundColorSpan(LIVE_RED), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (status != null) {
            s.append(status.toUpperCase(Locale.ROOT));
        } else {
            int start = s.length();
            s.append(Futbol.when(m, is24h).toUpperCase(Locale.ROOT));
            s.setSpan(new ForegroundColorSpan(0xFFFFFFFF), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    /** "Talleres 1 – 0 Belgrano", or "Talleres vs. Fortaleza" before kick-off. */
    private static CharSequence scoreLine(Futbol.Match m) {
        boolean scored = (m.live() || m.done()) && !m.homeScore.isEmpty() && !m.awayScore.isEmpty();
        if (!scored) return m.home + " vs. " + m.away;
        return m.home + "  " + m.homeScore + " – " + m.awayScore + "  " + m.away;
    }

    private TextView text(Context ctx, CharSequence value, float sp, int color, Typeface face) {
        return text(ctx, value, sp, color, face, 0.01f);
    }

    private TextView text(Context ctx, CharSequence value, float sp, int color, Typeface face, float spacing) {
        TextView t = new TextView(ctx);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setLetterSpacing(spacing);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.LEFT);
        // Over a bright video the text keeps a soft shadow, as the weather line does.
        t.setShadowLayer(6f, 0f, 0f, 0x99000000);
        t.setFocusable(false);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private String signature(List<Futbol.Block> blocks) {
        StringBuilder b = new StringBuilder();
        for (Futbol.Block block : blocks) {
            b.append(block.team.id).append('|');
            for (Futbol.Match m : block.matches) {
                b.append(m.id).append(m.state).append(m.statusName).append(m.clock)
                        .append(m.homeScore).append('-').append(m.awayScore).append(m.date).append(';');
            }
            // The "Hoy"/"Mañana" words change at midnight.
            b.append(java.time.LocalDate.now()).append('#');
        }
        return b.toString();
    }
}
