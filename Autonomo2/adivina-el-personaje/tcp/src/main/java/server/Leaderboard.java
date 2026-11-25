/*
 * Archivo : Leaderboard.java
 * Paquete : server
 *
 * Descripcion:
 *   Marcador global del servidor. Cumple el requisito minimo (marcador en
 *   memoria) y ademas el PUNTO EXTRA de persistencia: el estado se guarda en
 *   leaderboard.json y se vuelve a cargar al arrancar el servidor.
 *
 *   Concurrencia: varios clientes juegan a la vez en hilos distintos, asi que
 *   todas las operaciones se protegen con un ReadWriteLock. Las lecturas
 *   (consultar el marcador) pueden ser simultaneas; las escrituras (registrar
 *   una partida) son exclusivas.
 *
 *   Escritura segura: el archivo se escribe primero en un temporal y luego se
 *   mueve sobre el definitivo, para que una caida a mitad de escritura no deje
 *   el marcador corrupto.
 */

package server;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Marcador persistente y seguro frente a accesos concurrentes.
 */
public class Leaderboard {

    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Cuantas posiciones se envian al cliente. */
    public static final int TOP = 10;

    private final Map<String, ScoreEntry> entradas = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path archivo;
    private final boolean persistente;

    /**
     * @param rutaArchivo ruta del archivo de persistencia; si es null o vacia,
     *                    el marcador funciona solo en memoria
     */
    public Leaderboard(String rutaArchivo) {
        this.persistente = rutaArchivo != null && !rutaArchivo.trim().isEmpty();
        this.archivo = persistente ? Paths.get(rutaArchivo) : null;
        if (persistente) {
            cargar();
        }
    }

    // =======================================================================
    // Operaciones del juego
    // =======================================================================

    /**
     * Registra el resultado de una partida y persiste el marcador.
     *
     * @param jugador nombre del jugador
     * @param score   puntaje de la partida
     * @param gano    true si gano
     * @return la posicion del jugador en el marcador tras actualizar (1 = primero)
     */
    public int registrarPartida(String jugador, int score, boolean gano) {
        String nombre = normalizarNombre(jugador);
        String fecha = LocalDateTime.now().format(FORMATO_FECHA);

        lock.writeLock().lock();
        try {
            ScoreEntry e = entradas.get(nombre.toLowerCase());
            if (e == null) {
                e = new ScoreEntry(nombre);
                entradas.put(nombre.toLowerCase(), e);
            }
            e.registrarPartida(score, gano, fecha);
            guardar();
            return posicionDe(nombre);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * @return las {@link #TOP} mejores entradas, ya ordenadas
     */
    public List<ScoreEntry> top() {
        lock.readLock().lock();
        try {
            List<ScoreEntry> lista = new ArrayList<>(entradas.values());
            Collections.sort(lista);
            return lista.size() > TOP ? new ArrayList<>(lista.subList(0, TOP)) : lista;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Serializa el marcador como arreglo JSON, listo para el payload de un
     * mensaje LEADERBOARD. Cada entrada incluye su posicion ("rank").
     */
    public JSONArray toJsonArray() {
        JSONArray array = new JSONArray();
        int rank = 1;
        for (ScoreEntry e : top()) {
            JSONObject o = e.toJson();
            o.put("rank", rank++);
            array.put(o);
        }
        return array;
    }

    /** @return numero de jugadores registrados. */
    public int size() {
        lock.readLock().lock();
        try {
            return entradas.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** @return true si el marcador se esta guardando en disco. */
    public boolean esPersistente() {
        return persistente;
    }

    /** @return ruta del archivo de persistencia, o "(solo memoria)". */
    public String rutaArchivo() {
        return persistente ? archivo.toAbsolutePath().toString() : "(solo memoria)";
    }

    /** Posicion de un jugador en el marcador; 0 si no aparece. Requiere lock. */
    private int posicionDe(String nombre) {
        List<ScoreEntry> lista = new ArrayList<>(entradas.values());
        Collections.sort(lista);
        for (int i = 0; i < lista.size(); i++) {
            if (lista.get(i).getName().equalsIgnoreCase(nombre)) {
                return i + 1;
            }
        }
        return 0;
    }

    private String normalizarNombre(String nombre) {
        if (nombre == null || nombre.trim().isEmpty()) {
            return "Anonimo";
        }
        String limpio = nombre.trim().replaceAll("\\s+", " ");
        return limpio.length() > 20 ? limpio.substring(0, 20) : limpio;
    }

    // =======================================================================
    // Persistencia (PUNTO EXTRA)
    // =======================================================================

    /** Lee el marcador del disco al arrancar. Si falla, arranca vacio. */
    private void cargar() {
        if (!Files.isReadable(archivo)) {
            System.out.println("[Leaderboard] No existe " + archivo.toAbsolutePath()
                    + "; se creara al terminar la primera partida.");
            return;
        }
        try (Reader reader = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            JSONObject raiz = new JSONObject(new JSONTokener(reader));
            JSONArray array = raiz.optJSONArray("entries");
            if (array == null) {
                return;
            }
            for (int i = 0; i < array.length(); i++) {
                try {
                    ScoreEntry e = ScoreEntry.fromJson(array.getJSONObject(i));
                    entradas.put(e.getName().toLowerCase(), e);
                } catch (Exception ex) {
                    System.err.println("[Leaderboard] Entrada #" + i + " ignorada: "
                            + ex.getMessage());
                }
            }
            System.out.println("[Leaderboard] Cargado desde disco: " + entradas.size()
                    + " jugador(es).");
        } catch (Exception e) {
            System.err.println("[Leaderboard] No se pudo leer el marcador ("
                    + e.getMessage() + "). Se continuara en memoria.");
        }
    }

    /**
     * Guarda el marcador en disco de forma atomica.
     * Debe llamarse con el writeLock tomado.
     */
    private void guardar() {
        if (!persistente) {
            return;
        }
        try {
            JSONObject raiz = new JSONObject();
            raiz.put("updated", LocalDateTime.now().format(FORMATO_FECHA));

            JSONArray array = new JSONArray();
            List<ScoreEntry> lista = new ArrayList<>(entradas.values());
            Collections.sort(lista);
            for (ScoreEntry e : lista) {
                array.put(e.toJson());
            }
            raiz.put("entries", array);

            Path dir = archivo.toAbsolutePath().getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            // Escritura atomica: temporal + move, para no corromper el archivo.
            Path temporal = Paths.get(archivo.toAbsolutePath() + ".tmp");
            Files.write(temporal, raiz.toString(2).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporal, archivo,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicNoSoportado) {
                // Algunos sistemas de archivos no permiten ATOMIC_MOVE.
                Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Leaderboard] No se pudo guardar el marcador: "
                    + e.getMessage());
        }
    }

    // =======================================================================
    // Presentacion en consola (log del servidor)
    // =======================================================================

    /** Representacion en texto del marcador, para el log del servidor. */
    public String comoTexto() {
        List<ScoreEntry> lista = top();
        if (lista.isEmpty()) {
            return "   (marcador vacio)";
        }
        StringBuilder sb = new StringBuilder();
        int rank = 1;
        for (ScoreEntry e : lista) {
            sb.append(String.format("   %2d. %s%n", rank++, e));
        }
        return sb.toString();
    }
}
