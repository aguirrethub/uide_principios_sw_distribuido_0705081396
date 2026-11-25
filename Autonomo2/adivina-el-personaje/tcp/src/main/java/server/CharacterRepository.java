/*
 * Archivo : CharacterRepository.java
 * Paquete : server
 *
 * Descripcion:
 *   Carga el catalogo de personajes desde characters.json (en el classpath) y
 *   entrega personajes al azar sin repetir dentro de una misma partida.
 *
 *   Si el archivo falta o esta corrupto, el servidor NO se cae: se registra el
 *   error y se usa un catalogo minimo de respaldo, de modo que el juego siga
 *   siendo jugable. Esto cuenta como robustez ante fallos de configuracion.
 */

package server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Catalogo de personajes del juego.
 */
public class CharacterRepository {

    private static final String RECURSO = "characters.json";

    private final List<MovieCharacter> personajes = new ArrayList<>();
    private final Random random = new Random();

    /**
     * Construye el repositorio leyendo characters.json del classpath.
     * Ante cualquier fallo recurre al catalogo de respaldo.
     */
    public CharacterRepository() {
        try {
            cargarDesdeRecurso();
        } catch (Exception e) {
            System.err.println("[CharacterRepository] No se pudo leer " + RECURSO
                    + ": " + e.getMessage());
        }
        if (personajes.isEmpty()) {
            System.err.println("[CharacterRepository] Usando catalogo de respaldo.");
            cargarRespaldo();
        }
        System.out.println("[CharacterRepository] Personajes cargados: " + personajes.size());
    }

    // =======================================================================
    // Carga
    // =======================================================================

    private void cargarDesdeRecurso() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = CharacterRepository.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(RECURSO)) {
            if (in == null) {
                throw new IOException("No se encontro " + RECURSO + " en el classpath");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JSONObject raiz = new JSONObject(new JSONTokener(reader));
                JSONArray lista = raiz.getJSONArray("characters");

                for (int i = 0; i < lista.length(); i++) {
                    try {
                        personajes.add(construir(lista.getJSONObject(i)));
                    } catch (Exception e) {
                        // Un personaje mal formado no debe invalidar todo el catalogo.
                        System.err.println("[CharacterRepository] Personaje #" + i
                                + " ignorado por estar mal formado: " + e.getMessage());
                    }
                }
            }
        }
    }

    private MovieCharacter construir(JSONObject o) {
        String id = o.getString("id");
        String name = o.getString("name");
        String movie = o.optString("movie", "Desconocida");
        int year = o.optInt("year", 0);
        String image = o.optString("image", "");
        String reveal = o.optString("revealImage", image);

        List<String> clues = aLista(o.optJSONArray("clues"));
        if (clues.isEmpty()) {
            throw new IllegalArgumentException("El personaje '" + id + "' no tiene pistas");
        }
        List<String> aliases = aLista(o.optJSONArray("aliases"));

        return new MovieCharacter(id, name, movie, year, image, reveal, clues, aliases);
    }

    private List<String> aLista(JSONArray array) {
        List<String> salida = new ArrayList<>();
        if (array == null) {
            return salida;
        }
        for (int i = 0; i < array.length(); i++) {
            String s = array.optString(i, "").trim();
            if (!s.isEmpty()) {
                salida.add(s);
            }
        }
        return salida;
    }

    /** Catalogo minimo embebido por si characters.json no esta disponible. */
    private void cargarRespaldo() {
        personajes.add(new MovieCharacter(
                "vader", "Darth Vader", "Star Wars", 1977,
                "img/characters/vader_mystery.png", "img/characters/vader_reveal.png",
                Arrays.asList(
                        "Yo soy tu padre.",
                        "Respira con un sonido mecánico inconfundible y viste de negro.",
                        "Fue aprendiz de Obi-Wan Kenobi.",
                        "Su verdadero nombre es Anakin Skywalker."),
                Arrays.asList("vader", "darth vader", "anakin")));

        personajes.add(new MovieCharacter(
                "forrest", "Forrest Gump", "Forrest Gump", 1994,
                "img/characters/forrest_mystery.png", "img/characters/forrest_reveal.png",
                Arrays.asList(
                        "La vida es como una caja de chocolates.",
                        "Cuenta su historia sentado en la banca de una parada de autobús.",
                        "Corrió por todo Estados Unidos durante tres años.",
                        "Su amigo Bubba estaba obsesionado con los camarones."),
                Arrays.asList("forrest", "forrest gump", "gump")));

        personajes.add(new MovieCharacter(
                "rocky", "Rocky Balboa", "Rocky", 1976,
                "img/characters/rocky_mystery.png", "img/characters/rocky_reveal.png",
                Arrays.asList(
                        "¡Adriana!",
                        "Entrena subiendo las escaleras del museo de Filadelfia.",
                        "Golpea reses congeladas en un frigorífico.",
                        "Es un boxeador apodado El Semental Italiano."),
                Arrays.asList("rocky", "rocky balboa", "balboa")));
    }

    // =======================================================================
    // Consulta
    // =======================================================================

    /** @return todos los personajes del catalogo (lista no modificable). */
    public List<MovieCharacter> todos() {
        return Collections.unmodifiableList(personajes);
    }

    /** @return cuantos personajes hay disponibles. */
    public int size() {
        return personajes.size();
    }

    /**
     * Devuelve un personaje al azar evitando los ya usados en la partida.
     * Si todos fueron usados, permite repetir (para partidas largas).
     *
     * @param idsUsados ids de personajes ya mostrados en la partida actual
     * @return un personaje, o null si el catalogo esta vacio
     */
    public MovieCharacter aleatorio(Collection<String> idsUsados) {
        if (personajes.isEmpty()) {
            return null;
        }
        List<MovieCharacter> disponibles = new ArrayList<>();
        for (MovieCharacter p : personajes) {
            if (idsUsados == null || !idsUsados.contains(p.getId())) {
                disponibles.add(p);
            }
        }
        if (disponibles.isEmpty()) {
            disponibles = personajes; // ya se usaron todos: se permite repetir
        }
        return disponibles.get(random.nextInt(disponibles.size()));
    }
}
