/*
 * Archivo : ClientHandler.java
 * Paquete : server
 *
 * Descripcion:
 *   Atiende a UN cliente en su propio hilo. Su unica responsabilidad es
 *   traducir entre el socket y la GameSession:
 *
 *     socket --> bytes --> Message.parse() --> metodo de GameSession
 *     GameSession --> Message --> bytes --> socket
 *
 *   Toda la logica del juego vive en GameSession; aqui solo hay transporte,
 *   despacho de comandos y manejo de errores. Esa separacion es la que permite
 *   que el servidor atienda varios clientes a la vez sin duplicar reglas.
 *
 *   MANEJO DE ERRORES
 *   -----------------
 *   - JSON invalido, campo faltante o tipo desconocido -> se responde ERROR y
 *     la conexion CONTINUA abierta (un comando mal escrito no tumba la sesion).
 *   - Cliente desconectado de golpe (EOF / reset) -> se cierra limpiamente.
 *   - Excepcion inesperada -> se responde ERROR INTERNAL y se registra la
 *     traza en el log del servidor, sin tumbar el servidor completo.
 */

package server;

import common.JsonUtils;
import common.Message;
import common.NetworkUtils;
import common.Protocol;
import common.ProtocolException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hilo de atencion de un cliente TCP.
 */
public class ClientHandler implements Runnable, GameSession.Listener {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Contador global para numerar las conexiones en el log. */
    private static final AtomicInteger CONTADOR = new AtomicInteger(0);

    private final Socket socket;
    private final int idConexion;
    private final String etiquetaRemota;
    private final GameSession sesion;

    private OutputStream out;
    private InputStream in;
    private volatile boolean cerrado = false;

    public ClientHandler(Socket socket, CharacterRepository catalogo, Leaderboard marcador,
                         ImageStore imagenes, ScheduledExecutorService temporizador) {
        this.socket = socket;
        this.idConexion = CONTADOR.incrementAndGet();
        this.etiquetaRemota = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        this.sesion = new GameSession(catalogo, marcador, imagenes, temporizador, this);
    }

    // =======================================================================
    // Ciclo de vida de la conexion
    // =======================================================================

    @Override
    public void run() {
        log("Conexión aceptada desde " + etiquetaRemota);
        try {
            socket.setTcpNoDelay(true);   // sin retardo de Nagle: el juego es interactivo
            socket.setKeepAlive(true);    // detecta clientes que desaparecen

            out = socket.getOutputStream();
            in = socket.getInputStream();

            bucleDeMensajes();

        } catch (EOFException | SocketException e) {
            // Caso normal: el cliente cerro la ventana o perdio la red.
            log("El cliente se desconectó (" + e.getClass().getSimpleName() + ")");
        } catch (IOException e) {
            log("Error de E/S: " + e.getMessage());
        } catch (Exception e) {
            log("Error inesperado: " + e);
            e.printStackTrace();
        } finally {
            cerrarRecursos();
        }
    }

    /**
     * Bucle principal: recibe, despacha y repite hasta que el cliente se va
     * o la sesion queda marcada como terminada.
     */
    private void bucleDeMensajes() throws IOException {
        while (!cerrado && !sesion.terminado()) {
            byte[] bytes;
            try {
                bytes = NetworkUtils.receive(in);
            } catch (SocketTimeoutException e) {
                continue; // no deberia ocurrir (sin timeout), pero no es fatal
            }
            if (bytes.length == 0) {
                continue; // mensaje vacio: se ignora y se sigue escuchando
            }

            try {
                Message peticion = Message.parse(bytes);
                despachar(peticion);
            } catch (ProtocolException e) {
                // Error del cliente: se informa y la conexion sigue viva.
                log("Protocolo inválido [" + e.getCode() + "]: " + e.getMessage());
                enviar(Message.error(e));
            } catch (Exception e) {
                // Error del servidor: se informa sin romper la conexion.
                log("Fallo procesando el mensaje: " + e);
                enviar(Message.error(Protocol.ERR_INTERNAL,
                        "El servidor no pudo procesar la solicitud: " + e.getMessage()));
            }
        }
    }

    /**
     * Traduce el tipo de mensaje al metodo correspondiente de la sesion.
     * Un tipo desconocido produce un ERROR UNKNOWN_TYPE que enumera los
     * comandos validos, de modo que el cliente pueda corregirse.
     */
    private void despachar(Message peticion) throws ProtocolException {
        String tipo = peticion.getType();

        switch (tipo) {
            case Protocol.REQ_HELLO: {
                String nombre = peticion.optString(Protocol.P_NAME, "").trim();
                if (nombre.isEmpty()) {
                    enviar(Message.error(Protocol.ERR_INVALID_NAME,
                            "Debes enviar un nombre de jugador no vacío."));
                    return;
                }
                sesion.hello(nombre);
                break;
            }
            case Protocol.REQ_LEADERBOARD:
                sesion.leaderboard();
                break;

            case Protocol.REQ_START:
                sesion.start();
                break;

            case Protocol.REQ_GUESS: {
                // requireString valida presencia, tipo y que no venga vacio.
                String respuesta = JsonUtils.requireString(
                        peticion.getPayload(), Protocol.P_ANSWER);
                sesion.guess(respuesta);
                break;
            }
            case Protocol.REQ_MORE:
                sesion.more();
                break;

            case Protocol.REQ_NEXT:
                sesion.next();
                break;

            case Protocol.REQ_CONTINUE: {
                boolean otraVez = peticion.requireBoolean(Protocol.P_AGAIN);
                sesion.continuar(otraVez);
                break;
            }
            case Protocol.REQ_QUIT:
                sesion.quit();
                break;

            default:
                enviar(Message.error(Protocol.ERR_UNKNOWN_TYPE,
                        "Comando desconocido: '" + tipo + "'. Comandos válidos: "
                        + "HELLO, START, GUESS, MORE, NEXT, LEADERBOARD, CONTINUE, QUIT."));
                break;
        }
    }

    // =======================================================================
    // GameSession.Listener
    // =======================================================================

    /**
     * Envia un mensaje al cliente. Lo puede llamar tanto el hilo de comandos
     * como el del temporizador; NetworkUtils.send serializa las escrituras.
     */
    @Override
    public void enviar(Message mensaje) {
        if (cerrado || out == null) {
            return;
        }
        try {
            NetworkUtils.send(out, mensaje.toBytes());
        } catch (IOException e) {
            log("No se pudo enviar " + mensaje.getType() + ": " + e.getMessage());
            cerrado = true;
        }
    }

    @Override
    public void log(String texto) {
        System.out.println("[" + LocalTime.now().format(HORA) + "] [conn#" + idConexion
                + "] " + texto);
    }

    // =======================================================================
    // Cierre
    // =======================================================================

    private void cerrarRecursos() {
        cerrado = true;
        sesion.cerrar();
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            log("Error cerrando el socket: " + e.getMessage());
        }
        log("Conexión cerrada. El servidor sigue aceptando nuevos clientes.");
    }
}
