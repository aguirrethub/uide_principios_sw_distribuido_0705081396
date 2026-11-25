/*
 * Archivo : ServerConnection.java
 * Paquete : client
 *
 * Descripcion:
 *   Capa de red del cliente. Encapsula el socket TCP y, sobre todo, lee los
 *   mensajes del servidor en un HILO APARTE.
 *
 *   Ese hilo lector es imprescindible: el servidor no solo responde a lo que
 *   el cliente pide, tambien envia mensajes por iniciativa propia (el
 *   GAME_OVER que dispara el temporizador cuando se acaban los 60 segundos).
 *   Con un modelo peticion-respuesta sincrono ese mensaje llegaria tarde o
 *   bloquearia la interfaz. Ademas, leer en segundo plano mantiene la ventana
 *   de Swing siempre fluida.
 *
 *   Los mensajes recibidos se entregan a un Listener; quien lo implemente
 *   decide si los procesa en consola o en la interfaz grafica.
 */

package client;

import common.JsonUtils;
import common.Message;
import common.NetworkUtils;
import common.ProtocolException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import org.json.JSONObject;

/**
 * Conexion TCP del cliente con lectura asincrona.
 */
public class ServerConnection {

    /** Quien recibe los eventos de red. */
    public interface Listener {
        /** Llega un mensaje valido del servidor. */
        void onMessage(Message mensaje);

        /** El servidor envio algo que no se pudo interpretar. */
        void onProtocolError(ProtocolException e);

        /** La conexion se cerro (de forma ordenada o por un fallo). */
        void onDisconnected(String motivo);
    }

    /** Milisegundos de espera al establecer la conexion. */
    private static final int TIMEOUT_CONEXION_MS = 8000;

    private final String host;
    private final int puerto;
    private final Listener listener;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private Thread hiloLector;
    private volatile boolean activo = false;

    public ServerConnection(String host, int puerto, Listener listener) {
        this.host = host;
        this.puerto = puerto;
        this.listener = listener;
    }

    // =======================================================================
    // Conexion
    // =======================================================================

    /**
     * Abre la conexion y arranca el hilo lector.
     *
     * @throws IOException si el servidor no esta disponible
     */
    public void conectar() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, puerto), TIMEOUT_CONEXION_MS);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);

        out = socket.getOutputStream();
        in = socket.getInputStream();
        activo = true;

        hiloLector = new Thread(this::bucleLectura, "lector-servidor");
        hiloLector.setDaemon(true);
        hiloLector.start();
    }

    /** @return true si la conexion sigue abierta. */
    public boolean estaConectado() {
        return activo && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public String getHost() {
        return host;
    }

    public int getPuerto() {
        return puerto;
    }

    // =======================================================================
    // Envio
    // =======================================================================

    /**
     * Envia un mensaje al servidor.
     *
     * @return true si se pudo enviar
     */
    public boolean enviar(Message mensaje) {
        if (!estaConectado()) {
            listener.onDisconnected("No hay conexión con el servidor");
            return false;
        }
        try {
            NetworkUtils.send(out, mensaje.toBytes());
            return true;
        } catch (IOException e) {
            cerrarPorFallo("No se pudo enviar el mensaje: " + e.getMessage());
            return false;
        }
    }

    // =======================================================================
    // Lectura asincrona
    // =======================================================================

    private void bucleLectura() {
        try {
            while (activo) {
                byte[] bytes = NetworkUtils.receive(in);
                if (bytes.length == 0) {
                    continue;
                }
                try {
                    listener.onMessage(Message.parse(bytes));
                } catch (ProtocolException e) {
                    listener.onProtocolError(e);
                } catch (Exception e) {
                    listener.onProtocolError(new ProtocolException("CLIENT_ERROR",
                            "Error procesando la respuesta: " + e.getMessage(), e));
                }
            }
        } catch (EOFException e) {
            cerrarPorFallo("El servidor cerró la conexión");
        } catch (SocketException e) {
            // Tambien ocurre cuando somos nosotros quienes cerramos el socket.
            cerrarPorFallo(activo ? "Conexión perdida: " + e.getMessage()
                                  : "Conexión cerrada");
        } catch (IOException e) {
            cerrarPorFallo("Error de red: " + e.getMessage());
        }
    }

    // =======================================================================
    // Cierre
    // =======================================================================

    /** Cierre ordenado solicitado por el propio cliente. */
    public void cerrar() {
        if (!activo) {
            return;
        }
        activo = false;
        cerrarSocketSilencioso();
    }

    private void cerrarPorFallo(String motivo) {
        boolean estabaActivo = activo;
        activo = false;
        cerrarSocketSilencioso();
        if (estabaActivo) {
            listener.onDisconnected(motivo);
        }
    }

    private void cerrarSocketSilencioso() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Nada que hacer: la conexion ya no sirve.
        }
    }

    // =======================================================================
    // Utilidad para depuracion
    // =======================================================================

    /**
     * Devuelve el JSON de un mensaje en una linea, recortando la imagen.
     * Se usa en el modo "verbose" para mostrar el protocolo en el video.
     */
    public static String resumen(Message mensaje) {
        JSONObject json = mensaje.toJson();
        JSONObject payload = json.optJSONObject("payload");
        if (payload != null && payload.has("image")) {
            int largo = JsonUtils.optString(payload, "image", "").length();
            if (largo > 0) {
                payload.put("image", "<base64 " + largo + " chars>");
            }
        }
        return json.toString();
    }
}
