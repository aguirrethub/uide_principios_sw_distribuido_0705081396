/*
 * Archivo : ScoreEntry.java
 * Paquete : server
 *
 * Descripcion:
 *   Una fila del marcador: jugador, mejor puntaje, partidas jugadas, partidas
 *   ganadas y fecha de su ultima partida. Sabe serializarse a JSON y
 *   reconstruirse desde JSON, que es lo que permite que el marcador sea
 *   persistente entre ejecuciones del servidor.
 */

package server;

import org.json.JSONObject;

/**
 * Entrada del marcador correspondiente a un jugador.
 */
public class ScoreEntry implements Comparable<ScoreEntry> {

    private final String name;
    private int bestScore;
    private int games;
    private int wins;
    private String lastPlayed;

    public ScoreEntry(String name) {
        this.name = name;
        this.bestScore = 0;
        this.games = 0;
        this.wins = 0;
        this.lastPlayed = "";
    }

    public ScoreEntry(String name, int bestScore, int games, int wins, String lastPlayed) {
        this.name = name;
        this.bestScore = bestScore;
        this.games = games;
        this.wins = wins;
        this.lastPlayed = lastPlayed;
    }

    // =======================================================================
    // Accesores
    // =======================================================================

    public String getName() {
        return name;
    }

    public int getBestScore() {
        return bestScore;
    }

    public int getGames() {
        return games;
    }

    public int getWins() {
        return wins;
    }

    public String getLastPlayed() {
        return lastPlayed;
    }

    // =======================================================================
    // Actualizacion
    // =======================================================================

    /**
     * Registra el resultado de una partida.
     *
     * @param score  puntaje obtenido en esa partida
     * @param gano   true si el jugador gano
     * @param fecha  fecha legible de la partida
     */
    public void registrarPartida(int score, boolean gano, String fecha) {
        this.games++;
        if (gano) {
            this.wins++;
        }
        if (score > this.bestScore) {
            this.bestScore = score;
        }
        this.lastPlayed = fecha;
    }

    // =======================================================================
    // Orden: mayor puntaje primero; a igual puntaje, mas victorias; luego A-Z
    // =======================================================================

    @Override
    public int compareTo(ScoreEntry otro) {
        int porPuntaje = Integer.compare(otro.bestScore, this.bestScore);
        if (porPuntaje != 0) {
            return porPuntaje;
        }
        int porVictorias = Integer.compare(otro.wins, this.wins);
        if (porVictorias != 0) {
            return porVictorias;
        }
        return this.name.compareToIgnoreCase(otro.name);
    }

    // =======================================================================
    // Serializacion
    // =======================================================================

    /** Convierte la entrada a JSON para enviarla al cliente o guardarla. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("score", bestScore);
        o.put("games", games);
        o.put("wins", wins);
        o.put("date", lastPlayed);
        return o;
    }

    /** Reconstruye una entrada a partir de su JSON. */
    public static ScoreEntry fromJson(JSONObject o) {
        return new ScoreEntry(
                o.getString("name"),
                o.optInt("score", 0),
                o.optInt("games", 0),
                o.optInt("wins", 0),
                o.optString("date", ""));
    }

    @Override
    public String toString() {
        return String.format("%-16s %5d pts  %d/%d victorias  %s",
                name, bestScore, wins, games, lastPlayed);
    }
}
