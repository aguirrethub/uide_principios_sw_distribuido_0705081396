/*
 * Archivo : MovieCharacter.java
 * Paquete : server
 *
 * Descripcion:
 *   Modelo inmutable de un personaje de pelicula: su nombre, la pelicula,
 *   sus 4 pistas y las rutas de sus dos imagenes (misterio y revelacion).
 *
 *   La comparacion de respuestas se hace aqui (metodo matches) para que la
 *   logica de "que cuenta como respuesta correcta" viva en un solo sitio.
 *   Se normaliza el texto quitando tildes, signos y mayusculas, de modo que
 *   "DARTH VADER", "darth vader" y "Darth  Vader!" se acepten por igual.
 */

package server;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Un personaje jugable con sus pistas e imagenes asociadas.
 */
public class MovieCharacter {

    private final String id;
    private final String name;
    private final String movie;
    private final int year;
    private final String imagePath;
    private final String revealImagePath;
    private final List<String> clues;

    /** Formas aceptadas de escribir la respuesta, ya normalizadas. */
    private final Set<String> aliasesNormalizados;

    public MovieCharacter(String id, String name, String movie, int year,
                          String imagePath, String revealImagePath,
                          List<String> clues, List<String> aliases) {
        this.id = id;
        this.name = name;
        this.movie = movie;
        this.year = year;
        this.imagePath = imagePath;
        this.revealImagePath = revealImagePath;
        this.clues = Collections.unmodifiableList(new ArrayList<>(clues));

        Set<String> set = new LinkedHashSet<>();
        set.add(normalizar(name));
        for (String alias : aliases) {
            String n = normalizar(alias);
            if (!n.isEmpty()) {
                set.add(n);
            }
        }
        this.aliasesNormalizados = Collections.unmodifiableSet(set);
    }

    // =======================================================================
    // Accesores
    // =======================================================================

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getMovie() {
        return movie;
    }

    public int getYear() {
        return year;
    }

    public String getImagePath() {
        return imagePath;
    }

    public String getRevealImagePath() {
        return revealImagePath;
    }

    public List<String> getClues() {
        return clues;
    }

    /** @return numero de pistas disponibles (normalmente 4). */
    public int getClueCount() {
        return clues.size();
    }

    /**
     * Devuelve la pista en la posicion indicada, acotando el indice al rango
     * valido para que nunca se produzca un IndexOutOfBounds en tiempo de juego.
     *
     * @param index indice base 0
     */
    public String getClue(int index) {
        if (clues.isEmpty()) {
            return "(este personaje no tiene pistas configuradas)";
        }
        int i = Math.max(0, Math.min(index, clues.size() - 1));
        return clues.get(i);
    }

    // =======================================================================
    // Comparacion de respuestas
    // =======================================================================

    /**
     * Indica si la respuesta del jugador corresponde a este personaje.
     * Acepta el nombre completo o cualquiera de sus alias, sin distinguir
     * mayusculas, tildes ni signos de puntuacion.
     *
     * @param respuesta texto escrito por el jugador
     */
    public boolean matches(String respuesta) {
        if (respuesta == null) {
            return false;
        }
        String n = normalizar(respuesta);
        if (n.isEmpty()) {
            return false;
        }
        return aliasesNormalizados.contains(n);
    }

    /**
     * Normaliza un texto para compararlo: minusculas, sin tildes, sin signos
     * y con los espacios internos colapsados.
     *
     * Ejemplo: "  ¡Capitán Jack Sparrow!  " -> "capitan jack sparrow"
     */
    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String s = Normalizer.normalize(texto.trim().toLowerCase(), Normalizer.Form.NFD);
        s = s.replaceAll("\\p{InCombiningDiacriticalMarks}+", ""); // quita tildes
        s = s.replaceAll("[^a-z0-9 -]", " ");                      // quita signos
        s = s.replaceAll("\\s+", " ").trim();                      // colapsa espacios
        return s;
    }

    @Override
    public String toString() {
        return name + " (" + movie + ", " + year + ")";
    }
}
