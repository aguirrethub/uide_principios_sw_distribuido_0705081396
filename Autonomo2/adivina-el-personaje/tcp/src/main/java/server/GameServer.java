/*
 * Archivo : GameServer.java
 * Paquete : server
 *
 * Descripcion:
 *   Punto de entrada del servidor TCP. Abre el ServerSocket, acepta clientes
 *   en un bucle infinito y entrega cada conexion a un ClientHandler que corre
 *   en su propio hilo, tomado de un pool. Asi, cuando un cliente termina su
 *   partida y cierra, el servidor queda inmediatamente disponible para el
 *   siguiente, tal como exige el enunciado, y ademas puede atender a varios
 *   jugadores simultaneos.
 *
 *   Recursos compartidos entre todas las sesiones (creados una sola vez):
 *     - CharacterRepository : catalogo de personajes
 *     - ImageStore          : cache de imagenes en Base64
 *     - Leaderboard         : marcador persistente y thread-safe
 *     - ScheduledExecutor   : temporizadores de 60 s de todas las partidas
 *
 * Uso:
 *   gradle :tcp:runServer                -> puerto 8080 (por defecto)
 *   gradle :tcp:runServer -Pport=9099
 *   java server.GameServer [puerto]
 */

package server;

import common.Protocol;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor TCP del juego "Adivina el Personaje".
 */
public class GameServer {

    /** Archivo donde se guarda el marcador (punto extra: persistencia). */
    private static final String ARCHIVO_MARCADOR = "leaderboard.json";

    private final int puerto;
    private final CharacterRepository catalogo;
    private final ImageStore imagenes;
    private final Leaderboard marcador;
    private final ScheduledExecutorService temporizadores;
    private final java.util.concurrent.ExecutorService hilosClientes;

    private ServerSocket serverSocket;
    private volatile boolean enEjecucion = false;

    public GameServer(int puerto) {
        this.puerto = puerto;
        this.catalogo = new CharacterRepository();
        this.imagenes = new ImageStore();
        this.marcador = new Leaderboard(ARCHIVO_MARCADOR);

        this.temporizadores = Executors.newScheduledThreadPool(2,
                hiloDemonio("reloj-partida"));
        this.hilosClientes = Executors.newCachedThreadPool(
                hiloDemonio("cliente"));
    }

    /** Fabrica de hilos con nombre, para que los logs y el profiler se lean bien. */
    private static ThreadFactory hiloDemonio(final String prefijo) {
        final AtomicInteger n = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefijo + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // =======================================================================
    // Arranque
    // =======================================================================

    /** Abre el socket de escucha y entra en el bucle de aceptacion. */
    public void iniciar() {
        precargarImagenes();

        try {
            // 0.0.0.0 para aceptar tambien conexiones remotas (demo en AWS/VM).
            serverSocket = new ServerSocket(puerto, 50, InetAddress.getByName("0.0.0.0"));
            enEjecucion = true;
        } catch (BindException e) {
            System.err.println("ERROR: el puerto " + puerto + " ya está en uso.");
            System.err.println("       Cierra el proceso que lo ocupa o usa -Pport=<otro>.");
            System.exit(2);
            return;
        } catch (IOException e) {
            System.err.println("ERROR: no se pudo abrir el puerto " + puerto + ": "
                    + e.getMessage());
            System.exit(2);
            return;
        }

        banner();
        registrarApagadoOrdenado();

        while (enEjecucion) {
            try {
                Socket cliente = serverSocket.accept();
                hilosClientes.submit(
                        new ClientHandler(cliente, catalogo, marcador, imagenes, temporizadores));
            } catch (IOException e) {
                if (enEjecucion) {
                    System.err.println("[GameServer] Error aceptando conexión: "
                            + e.getMessage());
                }
                // Si enEjecucion es false, el socket se cerro a proposito: salimos.
            }
        }
    }

    /**
     * Carga las imagenes al arrancar en vez de en mitad de la partida, para
     * que la primera pista no llegue con retraso.
     */
    private void precargarImagenes() {
        imagenes.getBase64("img/win.png");
        imagenes.getBase64("img/lose.png");
        for (MovieCharacter p : catalogo.todos()) {
            imagenes.getBase64(p.getImagePath());
            imagenes.getBase64(p.getRevealImagePath());
        }
        System.out.println("[ImageStore] Imágenes precargadas: " + imagenes.cargadas());
    }

    private void banner() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("   ADIVINA EL PERSONAJE  -  SERVIDOR TCP");
        System.out.println("=================================================================");
        System.out.println("   Puerto            : " + puerto);
        System.out.println("   Protocolo          : JSON v" + Protocol.VERSION
                + " con prefijo de longitud (4 bytes)");
        System.out.println("   Personajes         : " + catalogo.size());
        System.out.println("   Reglas             : " + Protocol.FRASES_PARA_GANAR
                + " frases en " + Protocol.DURACION_PARTIDA_SEG + " s, "
                + Protocol.PISTAS_POR_PERSONAJE + " pistas por personaje");
        System.out.println("   Marcador           : " + marcador.rutaArchivo());
        System.out.println("   Jugadores en marcador: " + marcador.size());
        System.out.println("-----------------------------------------------------------------");
        System.out.println("   Esperando clientes... (Ctrl+C para detener)");
        System.out.println("=================================================================");
        System.out.println();
        if (marcador.size() > 0) {
            System.out.println("Marcador actual:");
            System.out.print(marcador.comoTexto());
            System.out.println();
        }
    }

    /** Cierra el socket y muestra el marcador final al recibir Ctrl+C. */
    private void registrarApagadoOrdenado() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            enEjecucion = false;
            System.out.println();
            System.out.println("[GameServer] Deteniendo el servidor...");
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException ignored) {
                // Nada que hacer: el proceso ya esta terminando.
            }
            hilosClientes.shutdownNow();
            temporizadores.shutdownNow();
            System.out.println("Marcador final:");
            System.out.print(marcador.comoTexto());
            System.out.println("[GameServer] Servidor detenido.");
        }, "apagado"));
    }

    // =======================================================================
    // main
    // =======================================================================

    public static void main(String[] args) {
        // Desde Java 18 System.out usa la codificacion de la consola, que en
        // Windows no suele ser UTF-8. Se fuerza aqui para que los acentos del
        // log se vean bien aunque el servidor se lance con "java" a secas.
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));

        int puerto = Protocol.DEFAULT_PORT;

        if (args.length >= 1) {
            try {
                puerto = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                System.err.println("ERROR: el puerto debe ser un número entero. "
                        + "Se recibió: '" + args[0] + "'");
                System.err.println("Uso: gradle :tcp:runServer -Pport=8080");
                System.exit(1);
            }
            if (puerto < 1 || puerto > 65535) {
                System.err.println("ERROR: el puerto debe estar entre 1 y 65535.");
                System.exit(1);
            }
        } else {
            System.out.println("(sin argumentos: se usa el puerto por defecto "
                    + Protocol.DEFAULT_PORT + ")");
        }

        new GameServer(puerto).iniciar();
    }
}
