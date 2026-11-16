package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launcher settings → Fútbol: switch the Home panel on or off, and choose, order and remove the teams it follows.
 * A team is added from a competition's list (Liga Profesional, Libertadores, the big European leagues, national
 * teams), read from ESPN. Built in code, with the same rows as the launcher's own settings.
 */
public final class FutbolSettingsActivity extends Activity {

    /** The lists a team can be picked from: label, ESPN league, and whether its teams are national teams. */
    private static final String[][] LEAGUES = {
            {"Argentina · Liga Profesional", "arg.1", "0"},
            {"Argentina · Primera Nacional", "arg.2", "0"},
            {"Copa Libertadores (clubes de Sudamérica)", "conmebol.libertadores", "0"},
            {"Copa Sudamericana", "conmebol.sudamericana", "0"},
            {"Brasil · Brasileirão", "bra.1", "0"},
            {"Uruguay · Primera División", "uru.1", "0"},
            {"España · LaLiga", "esp.1", "0"},
            {"Inglaterra · Premier League", "eng.1", "0"},
            {"Italia · Serie A", "ita.1", "0"},
            {"Alemania · Bundesliga", "ger.1", "0"},
            {"Francia · Ligue 1", "fra.1", "0"},
            {"Portugal · Primeira Liga", "por.1", "0"},
            {"Estados Unidos · MLS", "usa.1", "0"},
            {"México · Liga MX", "mex.1", "0"},
            {"Selecciones nacionales (Mundial)", "fifa.world", "1"},
            {"Selecciones de Sudamérica", "fifa.worldq.conmebol", "1"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private float density;
    private boolean loading;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101419);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(72), dp(38), dp(72), dp(38));
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        build(0);
    }

    /** Lays the screen out again and puts the focus on row {@code focus} (0: the on/off switch). */
    private void build(int focus) {
        list.removeAllViews();
        TextView title = label("Fútbol", 34, 0xFFFFFFFF);
        list.addView(title);
        TextView hint = label("Los resultados aparecen en la pantalla de inicio, debajo del clima. "
                + "Elegí hasta " + Futbol.MAX_TEAMS + " equipos; tocá uno para subirlo, bajarlo o quitarlo.", 14, 0xFFACB8C1);
        ((LinearLayout.LayoutParams) hint.getLayoutParams()).topMargin = dp(8);

        List<View> rows = new ArrayList<>();
        boolean on = Futbol.enabled(this);
        TextView toggle = row("Panel de fútbol: " + (on ? "Encendido" : "Apagado"), dp(28));
        toggle.setSelected(on);
        toggle.setOnClickListener(v -> {
            Futbol.prefs(this).edit().putBoolean(Futbol.KEY_ENABLED, !Futbol.enabled(this)).apply();
            build(0);
        });
        rows.add(toggle);

        TextView section = label("Equipos", 20, 0xFFFFFFFF);
        ((LinearLayout.LayoutParams) section.getLayoutParams()).topMargin = dp(28);

        List<Futbol.Team> teams = Futbol.teams(this);
        for (int i = 0; i < teams.size(); i++) {
            final int index = i;
            Futbol.Team t = teams.get(i);
            TextView r = row((i + 1) + ".  " + (t.national ? "Selección · " : "") + t.name, dp(10));
            r.setOnClickListener(v -> teamMenu(index));
            rows.add(r);
        }
        if (teams.isEmpty()) {
            TextView none = label("No hay equipos elegidos.", 16, 0xFFACB8C1);
            ((LinearLayout.LayoutParams) none.getLayoutParams()).topMargin = dp(12);
        }
        if (teams.size() < Futbol.MAX_TEAMS) {
            TextView add = row("+  Agregar equipo", dp(18));
            add.setOnClickListener(v -> pickLeague());
            rows.add(add);
        }
        TextView defaults = row("Volver a Talleres, Boca y Argentina", dp(28));
        defaults.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Equipos iniciales")
                .setMessage("¿Reemplazar la lista por Talleres, Boca Juniors y la Selección Argentina?")
                .setPositiveButton("Reemplazar", (d, w) -> {
                    Futbol.prefs(this).edit().remove(Futbol.KEY_TEAMS).apply();
                    build(1);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        rows.add(defaults);
        rows.get(Math.max(0, Math.min(focus, rows.size() - 1))).requestFocus();
    }

    private void teamMenu(int index) {
        List<Futbol.Team> teams = Futbol.teams(this);
        if (index >= teams.size()) return;
        Futbol.Team t = teams.get(index);
        String[] options = {"Subir", "Bajar", "Quitar"};
        new AlertDialog.Builder(this)
                .setTitle(t.name)
                .setItems(options, (d, which) -> {
                    List<Futbol.Team> now = Futbol.teams(this);
                    int focus = index + 1;
                    if (which == 0 && index > 0) {
                        now.add(index - 1, now.remove(index));
                        focus = index;
                    } else if (which == 1 && index < now.size() - 1) {
                        now.add(index + 1, now.remove(index));
                        focus = index + 2;
                    } else if (which == 2) {
                        now.remove(index);
                    }
                    Futbol.saveTeams(this, now);
                    build(focus);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickLeague() {
        String[] labels = new String[LEAGUES.length];
        for (int i = 0; i < LEAGUES.length; i++) labels[i] = LEAGUES[i][0];
        new AlertDialog.Builder(this)
                .setTitle("¿De qué competencia?")
                .setItems(labels, (d, which) -> loadTeams(LEAGUES[which][0], LEAGUES[which][1],
                        "1".equals(LEAGUES[which][2])))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void loadTeams(String label, String slug, boolean national) {
        if (loading) return;
        loading = true;
        Toast.makeText(this, "Buscando equipos…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String[]> found = readTeams(slug);
            main.post(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (found.isEmpty()) {
                    Toast.makeText(this, "No se pudo leer la lista. Revisá la conexión y probá de nuevo.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                pickTeam(label, found, national);
            });
        }, "futbol-teams").start();
    }

    private void pickTeam(String label, List<String[]> found, boolean national) {
        String[] names = new String[found.size()];
        for (int i = 0; i < found.size(); i++) names[i] = found.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(names, (d, which) -> {
                    String[] picked = found.get(which);
                    List<Futbol.Team> teams = Futbol.teams(this);
                    for (Futbol.Team t : teams) {
                        if (t.id.equals(picked[0])) {
                            Toast.makeText(this, picked[1] + " ya está en la lista", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    if (teams.size() >= Futbol.MAX_TEAMS) return;
                    teams.add(new Futbol.Team(picked[0], picked[1], national));
                    Futbol.saveTeams(this, teams);
                    build(teams.size());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** {id, name} for each team of an ESPN league, sorted by name. */
    private static List<String[]> readTeams(String slug) {
        List<String[]> out = new ArrayList<>();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://site.api.espn.com/apis/site/v2/sports/soccer/" + slug
                    + "/teams?limit=1000&lang=es&region=ar").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return out;
            String json;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bytes.size() < 4 * 1024 * 1024) bytes.write(buf, 0, n);
                json = bytes.toString("UTF-8");
            }
            JSONArray sports = new JSONObject(json).getJSONArray("sports");
            JSONArray teams = sports.getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams");
            for (int i = 0; i < teams.length(); i++) {
                JSONObject t = teams.getJSONObject(i).optJSONObject("team");
                if (t == null) continue;
                String id = t.optString("id", "");
                String name = t.optString("displayName", t.optString("name", ""));
                if (!id.isEmpty() && !name.isEmpty()) out.add(new String[]{id, name});
            }
        } catch (Exception ignored) {
            // An empty list tells the person to try again.
        } finally {
            if (c != null) c.disconnect();
        }
        Collator collator = Collator.getInstance(new Locale("es", "AR"));
        out.sort((a, b) -> collator.compare(a[1], b[1]));
        return out;
    }

    // ---- views ----

    private TextView label(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        list.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** A focusable row styled like the launcher settings' rows (SettingsRow). */
    private TextView row(String text, int topMargin) {
        TextView t = new TextView(this, null, 0, R.style.SettingsRow);
        t.setText(text);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = topMargin;
        list.addView(t, lp);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
