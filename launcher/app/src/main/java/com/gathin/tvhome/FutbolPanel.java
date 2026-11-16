package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the football panel under the weather line, across the screen: a glass card like the top pills with one
 * column per team, the team in bold (as the day name), then one match: the competition and its round, the score,
 * and the state with the day. A result and the next match take turns, eight seconds each; a live match stays.
 */
final class FutbolPanel {

    private static final int LIVE_RED = 0xFFFF4B3E;
    private static final int CELESTE = 0xFF75AADB;
    private static final long TURN_MS = 8000L;

    private final LinearLayout panel;
    private final Typeface medium;
    private final Typeface light;
    private final float density;
    private final boolean is24h;
    /** Each team's match views, shown one at a time. */
    private final List<View[]> slides = new ArrayList<>();
    private int turn;
    private boolean running;
    /** What is on screen, so an unchanged minute rebuilds nothing. */
    private String shown = "";

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            turn++;
            for (View[] s : slides) {
                if (s.length < 2) continue;
                View out = s[(turn - 1) % s.length];
                View in = s[turn % s.length];
                out.animate().cancel();
                out.animate().alpha(0f).setStartDelay(0).setDuration(250).withEndAction(() -> out.setVisibility(View.INVISIBLE)).start();
                in.animate().cancel();
                in.setAlpha(0f);
                in.setVisibility(View.VISIBLE);
                in.animate().alpha(1f).setStartDelay(200).setDuration(300).start();
            }
            panel.postDelayed(this, TURN_MS);
        }
    };

    FutbolPanel(LinearLayout panel, Typeface medium, Typeface light) {
        this.panel = panel;
        this.medium = medium;
        this.light = light;
        Context ctx = panel.getContext();
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.is24h = android.text.format.DateFormat.is24HourFormat(ctx);
        // The same glass as the top pills (pill_bg: Entradas, Ethernet, Ajustes), with their corners.
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFFFFF, 0x14FFFFFF});
        card.setCornerRadius(18 * density);
        card.setStroke(Math.max(1, Math.round(density)), 0x38FFFFFF);
        panel.setBackground(card);
        panel.setPadding(dp(18), dp(9), dp(18), dp(10));
    }

    /** Home is on screen (true) or not: the turns between a result and the next match only run while it shows. */
    void setRunning(boolean on) {
        if (running == on) return;
        running = on;
        panel.removeCallbacks(rotate);
        if (on) panel.postDelayed(rotate, TURN_MS);
    }

    void hide() {
        panel.setVisibility(View.GONE);
        panel.removeAllViews();
        slides.clear();
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
        slides.clear();
        int nationals = 0;
        for (Futbol.Block b : blocks) if (b.team.national) nationals++;
        for (int i = 0; i < blocks.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(22));
            panel.addView(team(blocks.get(i), nationals > 1), lp);
        }
        panel.setVisibility(View.VISIBLE);
    }

    private View team(Futbol.Block block, boolean nameNationals) {
        Context ctx = panel.getContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        // The team in bold, as the day name over the date; a short bar before it, celeste for a national team.
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(ctx);
        bar.setBackgroundColor(block.team.national ? CELESTE : 0xE6FFFFFF);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), dp(12));
        barLp.setMarginEnd(dp(9));
        head.addView(bar, barLp);
        String title = block.team.national
                ? (nameNationals ? "Selección " + block.team.name : "Selecciones nacionales") : block.team.name;
        TextView name = text(ctx, title, 13.5f, 0xFFFFFFFF, medium, 0.01f);
        name.setTypeface(medium, Typeface.BOLD);
        head.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(head);

        FrameLayout stage = new FrameLayout(ctx);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stageLp.topMargin = dp(4);
        stageLp.setMarginStart(dp(12));
        col.addView(stage, stageLp);

        List<Futbol.Match> matches = block.matches;
        if (matches.isEmpty()) {
            stage.addView(text(ctx, "Sin partidos por ahora", 12, 0xB3FFFFFF, light, 0.01f));
            return col;
        }
        // A live match stays put; otherwise the last result and the next match take turns.
        int count = matches.get(0).live() ? 1 : matches.size();
        View[] views = new View[count];
        for (int i = 0; i < count; i++) {
            View slide = slide(ctx, matches.get(i));
            boolean on = i == turn % count;
            slide.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            stage.addView(slide, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            views[i] = slide;
        }
        slides.add(views);
        return col;
    }

    /** Three lines: the competition and its round, the score, and the state with the day. */
    private View slide(Context ctx, Futbol.Match m) {
        LinearLayout s = new LinearLayout(ctx);
        s.setOrientation(LinearLayout.VERTICAL);
        s.addView(text(ctx, kicker(m), 9.5f, 0xD9FFFFFF, medium, 0.06f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        s.addView(text(ctx, scoreLine(m), 13.5f, 0xFFFFFFFF, medium, 0f), lp);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(3);
        s.addView(text(ctx, meta(m), 9.5f, 0xB3FFFFFF, medium, 0.06f), metaLp);
        return s;
    }

    /** "LIGA PROFESIONAL · FECHA 14", "COPA LIBERTADORES · OCTAVOS DE FINAL (IDA)", "AMISTOSO INTERNACIONAL". */
    private static CharSequence kicker(Futbol.Match m) {
        String k = Futbol.competition(m);
        if (m.round != null && !m.round.isEmpty()) k += "  ·  " + m.round;
        return k.toUpperCase(Locale.ROOT);
    }

    /** "FINAL · SÁB 15/11", "● EN VIVO 71'", "HOY 21:00". */
    private CharSequence meta(Futbol.Match m) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        String status = Futbol.status(m);
        if (m.live()) {
            s.append("● EN VIVO ").append(status == null ? "" : status.toUpperCase(Locale.ROOT));
            s.setSpan(new ForegroundColorSpan(LIVE_RED), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (status != null) {
            s.append(status.toUpperCase(Locale.ROOT)).append("  ·  ").append(Futbol.playedOn(m).toUpperCase(Locale.ROOT));
        } else {
            s.append(Futbol.when(m, is24h).toUpperCase(Locale.ROOT));
            s.setSpan(new ForegroundColorSpan(CELESTE), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    /** "Talleres  1 – 1  Estudiantes", or "Atlético-MG vs. Boca Juniors" before kick-off. */
    private static CharSequence scoreLine(Futbol.Match m) {
        boolean scored = (m.live() || m.done()) && !m.homeScore.isEmpty() && !m.awayScore.isEmpty();
        if (!scored) return m.home + " vs. " + m.away;
        return m.home + "  " + m.homeScore + " – " + m.awayScore + "  " + m.away;
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
        t.setShadowLayer(4f, 0f, 1f, 0x80000000);
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
            b.append(block.team.id).append(block.team.name).append('|');
            for (Futbol.Match m : block.matches) {
                b.append(m.id).append(m.round).append(m.state).append(m.statusName).append(m.clock)
                        .append(m.homeScore).append('-').append(m.awayScore).append(m.date).append(';');
            }
        }
        // The "Hoy"/"Mañana" words change at midnight.
        return b.append(java.time.LocalDate.now()).toString();
    }
}
