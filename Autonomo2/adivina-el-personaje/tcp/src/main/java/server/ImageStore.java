/*
 * Archivo : ImageStore.java
 * Paquete : server
 *
 * Descripcion:
 *   Carga las imagenes PNG y las entrega codificadas en Base64 para poder
 *   viajar dentro de un mensaje JSON (JSON no admite datos binarios crudos).
 *
 *   Busca cada imagen en dos sitios, en este orden:
 *     1. el classpath (asi funciona empaquetado en un jar y con gradle run)
 *     2. el sistema de archivos relativo al directorio de trabajo y a la raiz
 *        del proyecto (asi funciona si alguien mueve la carpeta img/)
 *
 *   Mantiene una cache en memoria: cada PNG se lee del disco una sola vez
 *   aunque lo pidan muchos clientes concurrentes.
 */

package server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carga y cachea imagenes del juego en formato Base64.
 */
public class ImageStore {

    /** Cache ruta -> contenido Base64. Concurrente: la comparten varios hilos. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Directorios donde buscar si la imagen no esta en el classpath. */
    private static final String[] RAICES_ALTERNATIVAS = {
        "", "..", "../..", "tcp", "udp"
    };

    /**
     * Devuelve la imagen indicada codificada en Base64.
     *
     * @param ruta ruta relativa, por ejemplo "img/win.png"
     * @return la imagen en Base64, o cadena vacia si no se pudo cargar
     */
    public String getBase64(String ruta) {
        if (ruta == null || ruta.trim().isEmpty()) {
            return "";
        }
        // computeIfAbsent garantiza que la lectura ocurra una sola vez.
        return cache.computeIfAbsent(ruta, this::cargar);
    }

    /** Indica si la imagen existe y se pudo leer. */
    public boolean existe(String ruta) {
        return !getBase64(ruta).isEmpty();
    }

    /** Numero de imagenes cargadas en cache (util para el log de arranque). */
    public int cargadas() {
        int n = 0;
        for (String v : cache.values()) {
            if (!v.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    // =======================================================================
    // Carga efectiva
    // =======================================================================

    private String cargar(String ruta) {
        byte[] datos = leerDeClasspath(ruta);
        if (datos == null) {
            datos = leerDeDisco(ruta);
        }
        if (datos == null) {
            System.err.println("[ImageStore] AVISO: no se encontro la imagen '" + ruta
                    + "'. El juego continuara sin ella.");
            return "";
        }
        return Base64.getEncoder().encodeToString(datos);
    }

    private byte[] leerDeClasspath(String ruta) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ImageStore.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(ruta)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] leerDeDisco(String ruta) {
        for (String raiz : RAICES_ALTERNATIVAS) {
            try {
                Path p = raiz.isEmpty() ? Paths.get(ruta) : Paths.get(raiz, ruta);
                if (Files.isReadable(p)) {
                    return Files.readAllBytes(p);
                }
            } catch (Exception ignored) {
                // Ruta invalida en esta plataforma: se prueba la siguiente.
            }
        }
        return null;
    }
}
