package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Home football panel: for each chosen team (Talleres, Boca and Argentina out of the box) its live match, its
 * last result and its next match, each under the name of its competition. Data from ESPN's public JSON (no key):
 * a team's schedule across all competitions, and the league scoreboard while a match is on, so the score and the
 * minute follow the game. Fetched off the UI thread; the last answers are kept, so Home shows them at once.
 */
final class Futbol {

    static final String PREFS = "futbol";
    static final String KEY_TEAMS = "teams";
    static final String KEY_ENABLED = "enabled";
    /** The panel has room for this many teams side by side. */
    static final int MAX_TEAMS = 4;

    private static final String API = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    private static final String LANG = "lang=es&region=ar";
    /** A team's schedule is read again after this long... */
    private static final long SCHEDULE_MS = 15 * 60 * 1000L;
    /** ...or this long when one of its matches starts or ends around now, so the change shows up soon. */
    private static final long SCHEDULE_NEAR_MS = 3 * 60 * 1000L;
    /** While a match is on, its scoreboard is read every minute (Home's minute tick). */
    private static final long LIVE_MS = 55 * 1000L;
    /** A match counts as possibly on from its kick-off until this long after it. */
    private static final Duration MATCH_WINDOW = Duration.ofHours(3);

    /** One team to follow. */
    static final class Team {
        final String id;
        final String name;
        final boolean national;

        Team(String id, String name, boolean national) {
            this.id = id;
            this.name = name;
            this.national = national;
        }
    }

    /** One match as the panel shows it. */
    static final class Match {
        String id;
        Instant date;
        String leagueSlug = "";
        String leagueName = "";
        String home = "";
        String away = "";
        String homeScore = "";
        String awayScore = "";
        /** ESPN's state: pre, in or post. */
        String state = "pre";
        /** ESPN's status name, such as STATUS_HALFTIME or STATUS_FINAL_PEN. */
        String statusName = "";
        String clock = "";
        String detail = "";

        boolean live() {
            return "in".equals(state);
        }

        boolean done() {
            return "post".equals(state);
        }
    }

    /** What one column of the panel shows. */
    static final class Block {
        final Team team;
        final List<Match> matches;

        Block(Team team, List<Match> matches) {
            this.team = team;
            this.matches = matches;
        }
    }

    interface Listener {
        void onFutbol(List<Block> blocks);
    }

    private final Context ctx;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Live scores read from a scoreboard, by match id: newer than the cached schedules. */
    private final Map<String, Match> liveScores = new HashMap<>();
    private final Map<String, Long> liveReadAt = new HashMap<>();
    private boolean fetching;

    Futbol(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.cache = this.ctx.getSharedPreferences("futbol_cache", Context.MODE_PRIVATE);
    }

    // ---- the chosen teams ----------------------------------------------------------------------------------------

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    /** The teams in the order the panel shows them; Talleres, Boca Juniors and Argentina until changed. */
    static List<Team> teams(Context ctx) {
        String saved = prefs(ctx).getString(KEY_TEAMS, null);
        List<Team> teams = new ArrayList<>();
        if (saved == null) {
            teams.add(new Team("19", "Talleres (Córdoba)", false));
            teams.add(new Team("5", "Boca Juniors", false));
            teams.add(new Team("202", "Argentina", true));
            return teams;
        }
        try {
            JSONArray a = new JSONArray(saved);
            for (int i = 0; i < a.length() && teams.size() < MAX_TEAMS; i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                teams.add(new Team(id, o.optString("name", id), o.optBoolean("national", false)));
            }
        } catch (Exception ignored) {
            // An unreadable list shows no teams rather than crashing Home.
        }
        return teams;
    }

    static void saveTeams(Context ctx, List<Team> teams) {
        JSONArray a = new JSONArray();
        try {
            for (Team t : teams) {
                a.put(new JSONObject().put("id", t.id).put("name", t.name).put("national", t.national));
            }
        } catch (Exception ignored) {
            return;
        }
        prefs(ctx).edit().putString(KEY_TEAMS, a.toString()).apply();
    }

    // ---- updating ------------------------------------------------------------------------------------------------

    /** Shows what is cached right away, then reads whatever is due off the UI thread and shows the result. */
    void update(Listener show) {
        List<Team> teams = teams(ctx);
        show.onFutbol(blocks(teams));
        if (fetching) return;
        long now = System.currentTimeMillis();
        List<Team> due = new ArrayList<>();
        for (Team t : teams) {
            long age = now - cache.getLong("at_" + t.id, 0L);
            long every = near(t, Instant.now()) ? SCHEDULE_NEAR_MS : SCHEDULE_MS;
            if (age >= every || age < 0) due.add(t);
        }
        List<Match> liveDue = new ArrayList<>();
        for (Team t : teams) {
            for (Match m : allMatches(t)) {
                if (!possiblyOn(m, Instant.now())) continue;
                Long at = liveReadAt.get(m.id);
                if (at == null || now - at >= LIVE_MS) liveDue.add(m);
            }
        }
        if (due.isEmpty() && liveDue.isEmpty()) return;
        fetching = true;
        new Thread(() -> {
            Map<String, String[]> schedules = new HashMap<>();
            for (Team t : due) {
                String results = fetch(API + "all/teams/" + t.id + "/schedule?" + LANG);
                String fixtures = fetch(API + "all/teams/" + t.id + "/schedule?fixture=true&" + LANG);
                schedules.put(t.id, new String[]{results, fixtures});
            }
            Map<String, Match> live = new HashMap<>();
            for (Match m : liveDue) {
                Match fresh = readScoreboard(m);
                if (fresh != null) live.put(m.id, fresh);
            }
            main.post(() -> {
                fetching = false;
                long at = System.currentTimeMillis();
                SharedPreferences.Editor e = cache.edit();
                for (Map.Entry<String, String[]> s : schedules.entrySet()) {
                    String[] pair = s.getValue();
                    // Keep the last good answer when a read fails; try again at the next tick after a short wait.
                    if (pair[0] != null) e.putString("results_" + s.getKey(), pair[0]);
                    if (pair[1] != null) e.putString("fixtures_" + s.getKey(), pair[1]);
                    if (pair[0] != null || pair[1] != null) e.putLong("at_" + s.getKey(), at);
                }
                e.apply();
                for (Match m : liveDue) liveReadAt.put(m.id, at);
                liveScores.putAll(live);
                show.onFutbol(blocks(teams(ctx)));
            });
        }, "futbol").start();
    }

    /** Forgets the live scores read so far, after the list of teams changed. */
    void reset() {
        liveScores.clear();
        liveReadAt.clear();
    }

    // ---- choosing what to show -----------------------------------------------------------------------------------

    private List<Block> blocks(List<Team> teams) {
        List<Block> out = new ArrayList<>();
        Instant now = Instant.now();
        for (Team t : teams) out.add(new Block(t, pick(allMatches(t), now)));
        return out;
    }

    /** A live match and the last result, or the last result and the next match; at most two. */
    static List<Match> pick(List<Match> matches, Instant now) {
        Match live = null, last = null, next = null;
        for (Match m : matches) {
            if (m.live()) {
                if (live == null || m.date.isAfter(live.date)) live = m;
            } else if (m.done()) {
                if (last == null || m.date.isAfter(last.date)) last = m;
            } else if (m.date != null && !m.date.isBefore(now.minus(MATCH_WINDOW))) {
                if (next == null || m.date.isBefore(next.date)) next = m;
            }
        }
        List<Match> out = new ArrayList<>();
        if (live != null) {
            out.add(live);
            if (last != null) out.add(last);
            else if (next != null) out.add(next);
        } else {
            if (last != null) out.add(last);
            if (next != null) out.add(next);
        }
        return out;
    }

    /** Every match known for a team: its schedules, with any newer live score laid over them. */
    private List<Match> allMatches(Team t) {
        Map<String, Match> byId = new HashMap<>();
        for (Match m : parseSchedule(cache.getString("results_" + t.id, null))) byId.put(m.id, m);
        for (Match m : parseSchedule(cache.getString("fixtures_" + t.id, null))) {
            Match known = byId.get(m.id);
            // The results list may already hold the final score of a match the fixtures list still has as upcoming.
            if (known == null || !known.done()) byId.put(m.id, m);
        }
        for (Map.Entry<String, Match> e : liveScores.entrySet()) {
            if (byId.containsKey(e.getKey())) byId.put(e.getKey(), e.getValue());
        }
        List<Match> list = new ArrayList<>(byId.values());
        list.removeIf(m -> m.date == null);
        Collections.sort(list, (a, b) -> a.date.compareTo(b.date));
        return list;
    }

    private static boolean possiblyOn(Match m, Instant now) {
        if (m.live()) return true;
        if (m.done() || m.date == null) return false;
        return !now.isBefore(m.date.minusSeconds(60)) && now.isBefore(m.date.plus(MATCH_WINDOW));
    }

    private boolean near(Team t, Instant now) {
        for (Match m : allMatches(t)) {
            if (m.date == null) continue;
            if (Math.abs(Duration.between(now, m.date).toMinutes()) < 200 && !m.done()) return true;
        }
        return false;
    }

    // ---- reading ESPN ----------------------------------------------------------------------------------------------

    /** The match's line on its league's scoreboard, which follows the game minute by minute; null if not found. */
    private Match readScoreboard(Match m) {
        if (m.leagueSlug.isEmpty() || m.date == null) return null;
        // The scoreboard's days are US Eastern days; an evening match in Argentina can be on the next UTC day.
        DateTimeFormatter day = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
        String east = day.format(m.date.atZone(ZoneId.of("America/New_York")));
        String utc = day.format(m.date.atZone(ZoneId.of("UTC")));
        for (String d : east.equals(utc) ? new String[]{east} : new String[]{east, utc}) {
            String json = fetch(API + m.leagueSlug + "/scoreboard?dates=" + d + "&" + LANG);
            if (json == null) continue;
            try {
                JSONArray events = new JSONObject(json).optJSONArray("events");
                if (events == null) continue;
                for (int i = 0; i < events.length(); i++) {
                    JSONObject ev = events.getJSONObject(i);
                    if (!m.id.equals(ev.optString("id"))) continue;
                    Match fresh = parseEvent(ev, m.leagueSlug, m.leagueName);
                    return fresh;
                }
            } catch (Exception ignored) {
                // An answer it cannot read: the schedule's score stays on screen.
            }
        }
        return null;
    }

    static List<Match> parseSchedule(String json) {
        List<Match> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            if (events == null) return out;
            for (int i = 0; i < events.length(); i++) {
                Match m = parseEvent(events.getJSONObject(i), null, null);
                if (m != null) out.add(m);
            }
        } catch (Exception ignored) {
            // Nothing readable: an empty list.
        }
        return out;
    }

    /** One ESPN event; slug and name fill in the league when the event itself does not carry it (scoreboards). */
    static Match parseEvent(JSONObject ev, String slug, String name) {
        try {
            Match m = new Match();
            m.id = ev.getString("id");
            m.date = parseDate(ev.optString("date", null));
            JSONObject league = ev.optJSONObject("league");
            m.leagueSlug = league != null ? league.optString("slug", "") : "";
            m.leagueName = league != null ? league.optString("name", "") : "";
            if (m.leagueSlug.isEmpty() && slug != null) m.leagueSlug = slug;
            if (m.leagueName.isEmpty() && name != null) m.leagueName = name;
            JSONArray comps = ev.optJSONArray("competitions");
            JSONObject comp = comps != null && comps.length() > 0 ? comps.getJSONObject(0) : null;
            JSONObject status = comp != null && comp.has("status") ? comp.getJSONObject("status") : ev.optJSONObject("status");
            if (status != null) {
                JSONObject type = status.optJSONObject("type");
                if (type != null) {
                    m.state = type.optString("state", "pre");
                    m.statusName = type.optString("name", "");
                    m.detail = type.optString("shortDetail", "");
                }
                m.clock = status.optString("displayClock", "");
            }
            if (comp != null) {
                if (m.date == null) m.date = parseDate(comp.optString("date", null));
                JSONArray teams = comp.optJSONArray("competitors");
                if (teams != null) {
                    for (int i = 0; i < teams.length(); i++) {
                        JSONObject c = teams.getJSONObject(i);
                        JSONObject team = c.optJSONObject("team");
                        String teamName = team == null ? "" : firstNonEmpty(team.optString("shortDisplayName"),
                                team.optString("displayName"), team.optString("name"));
                        String score = score(c.opt("score"));
                        boolean home = "home".equals(c.optString("homeAway")) || (i == 0 && !c.has("homeAway"));
                        if (home) {
                            m.home = teamName;
                            m.homeScore = score;
                        } else {
                            m.away = teamName;
                            m.awayScore = score;
                        }
                    }
                }
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** Schedules give the score as an object with a displayValue, scoreboards as a plain string. */
    private static String score(Object s) {
        if (s == null || s == JSONObject.NULL) return "";
        if (s instanceof JSONObject) {
            JSONObject o = (JSONObject) s;
            String v = o.optString("displayValue", "");
            if (!v.isEmpty()) return v;
            if (o.has("value")) return String.valueOf(Math.round(o.optDouble("value")));
            return "";
        }
        if (s instanceof Number) return String.valueOf(Math.round(((Number) s).doubleValue()));
        return s.toString();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    /** ESPN writes "2026-08-28T18:45Z", without seconds. */
    static Instant parseDate(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            try {
                return Instant.parse(s.replace("Z", ":00Z"));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String fetch(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                // A season's schedule is a few hundred KB at most; anything bigger is not what was asked for.
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 2 * 1024 * 1024) return null;
                }
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- words ---------------------------------------------------------------------------------------------------

    /** The competition as people in Argentina name it: Liga Profesional, Copa Libertadores, Eliminatorias... */
    static String competition(Match m) {
        String slug = m.leagueSlug == null ? "" : m.leagueSlug.toLowerCase(Locale.ROOT);
        switch (slug) {
            case "arg.1": return "Liga Profesional";
            case "arg.2": return "Primera Nacional";
            case "arg.copa": return "Copa Argentina";
            case "arg.supercopa": return "Supercopa Argentina";
            case "arg.trofeo_de_campeones": return "Trofeo de Campeones";
            case "conmebol.libertadores": return "Copa Libertadores";
            case "conmebol.sudamericana": return "Copa Sudamericana";
            case "conmebol.recopa": return "Recopa Sudamericana";
            case "conmebol.america": return "Copa América";
            case "fifa.world": return "Mundial";
            case "fifa.cwc": return "Mundial de Clubes";
            case "fifa.friendly": return "Amistoso internacional";
            case "club.friendly": return "Amistoso";
            case "fifa.olympics": return "Juegos Olímpicos";
            default: break;
        }
        if (slug.startsWith("fifa.worldq")) return "Eliminatorias";
        String name = m.leagueName == null ? "" : m.leagueName.trim();
        if (name.isEmpty()) return "Fútbol";
        name = name.replace("Argentine ", "").replace("CONMEBOL ", "Copa ").replace("Copa Copa", "Copa");
        return name;
    }

    /** "Final", "Entretiempo", "71'", "Postergado". */
    static String status(Match m) {
        String n = m.statusName == null ? "" : m.statusName;
        if (m.live()) {
            if (n.contains("HALFTIME")) return "Entretiempo";
            if (n.contains("SHOOTOUT") || n.contains("PENALT")) return "Penales";
            if (m.clock != null && !m.clock.isEmpty() && !m.clock.startsWith("0:00")) return m.clock;
            return m.detail == null || m.detail.isEmpty() ? "En juego" : m.detail;
        }
        if (n.contains("POSTPONED")) return "Postergado";
        if (n.contains("CANCELED") || n.contains("CANCELLED") || n.contains("ABANDONED")) return "Suspendido";
        if (m.done()) {
            if (n.contains("PEN")) return "Final · penales";
            if (n.contains("AET") || n.contains("EXTRA")) return "Final · alargue";
            return "Final";
        }
        return null;
    }

    /** "Hoy 21:00", "Mañana 19:15", "Jue 20/11 21:30", in the TV's time zone. */
    static String when(Match m, boolean is24h) {
        if (m.date == null) return "";
        ZoneId zone = ZoneId.systemDefault();
        java.time.ZonedDateTime at = m.date.atZone(zone);
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        Locale es = new Locale("es", "AR");
        String time = at.format(DateTimeFormatter.ofPattern(is24h ? "HH:mm" : "h:mm a", es));
        if (at.toLocalDate().equals(today)) return "Hoy " + time;
        if (at.toLocalDate().equals(today.plusDays(1))) return "Mañana " + time;
        String dayName = at.format(DateTimeFormatter.ofPattern("EEE", es)).replace(".", "");
        if (!dayName.isEmpty()) dayName = dayName.substring(0, 1).toUpperCase(es) + dayName.substring(1);
        return dayName + " " + at.format(DateTimeFormatter.ofPattern("d/M", es)) + " " + time;
    }
}
