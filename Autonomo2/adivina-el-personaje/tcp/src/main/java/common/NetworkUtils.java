/*
 * Archivo : NetworkUtils.java
 * Paquete : common
 *
 * Descripcion:
 *   Utilidades de transporte sobre TCP.
 *
 *   PROBLEMA QUE RESUELVE: TCP entrega un flujo continuo de bytes, no mensajes.
 *   Una sola llamada a read() puede devolver medio mensaje, un mensaje completo
 *   o varios mensajes pegados. Por eso cada mensaje se envia con un prefijo de
 *   4 bytes big-endian que indica su longitud ("length-prefix framing"):
 *
 *        +----------+----------------------------+
 *        | long (4) |  bytes JSON (UTF-8)        |
 *        +----------+----------------------------+
 *
 *   Ademas se corrige un defecto del codigo base: InputStream.read() puede
 *   devolver menos bytes de los pedidos. Aqui se lee en bucle hasta completar
 *   la cantidad exacta (readFully), que es lo correcto para mensajes grandes
 *   como las imagenes en Base64.
 */

package common;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Envio y recepcion de mensajes delimitados por longitud sobre un socket TCP.
 * Clase de utilidad: no se instancia.
 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    // =======================================================================
    // Conversion int <-> byte[] (big-endian, orden de red)
    // =======================================================================

    /** Convierte un entero de 32 bits a 4 bytes en orden de red. */
    public static byte[] intToBytes(final int data) {
        return new byte[] {
            (byte) ((data >> 24) & 0xff),
            (byte) ((data >> 16) & 0xff),
            (byte) ((data >> 8) & 0xff),
            (byte) (data & 0xff)
        };
    }

    /** Convierte 4 bytes en orden de red a un entero de 32 bits. */
    public static int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24)
             | ((bytes[1] & 0xFF) << 16)
             | ((bytes[2] & 0xFF) << 8)
             | (bytes[3] & 0xFF);
    }

    // =======================================================================
    // Envio
    // =======================================================================

    /**
     * Envia un bloque de bytes precedido de su longitud.
     * Es synchronized sobre el stream para que dos hilos (por ejemplo, el hilo
     * del temporizador y el que atiende comandos) no entrelacen escrituras.
     *
     * @param out   flujo de salida del socket
     * @param bytes contenido a enviar
     */
    public static void send(OutputStream out, byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("No se puede enviar un mensaje nulo");
        }
        synchronized (out) {
            out.write(intToBytes(bytes.length));
            out.write(bytes);
            out.flush();
        }
    }

    /** Atajo para enviar directamente una cadena en UTF-8. */
    public static void sendString(OutputStream out, String text) throws IOException {
        send(out, text.getBytes(StandardCharsets.UTF_8));
    }

    // =======================================================================
    // Recepcion
    // =======================================================================

    /**
     * Lee exactamente {@code length} bytes del flujo, reintentando hasta
     * completarlos. Lanza EOFException si la conexion se corta antes.
     */
    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new EOFException(
                        "La conexion se cerro tras leer " + offset + " de " + length + " bytes");
            }
            offset += read;
        }
        return buffer;
    }

    /**
     * Recibe un mensaje completo: lee la cabecera de 4 bytes y luego el cuerpo.
     *
     * @return los bytes del mensaje
     * @throws EOFException si el otro extremo cerro la conexion
     * @throws IOException  ante un error de red o una longitud invalida
     */
    public static byte[] receive(InputStream in) throws IOException {
        byte[] header = readFully(in, 4);
        int length = bytesToInt(header);

        if (length < 0 || length > Protocol.MAX_MESSAGE_BYTES) {
            throw new IOException("Longitud de mensaje invalida o excesiva: " + length + " bytes");
        }
        if (length == 0) {
            return new byte[0];
        }
        return readFully(in, length);
    }

    /** Recibe un mensaje y lo devuelve como cadena UTF-8. */
    public static String receiveString(InputStream in) throws IOException {
        return new String(receive(in), StandardCharsets.UTF_8);
    }
}
