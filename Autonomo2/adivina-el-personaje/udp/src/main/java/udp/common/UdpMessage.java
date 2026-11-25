/*
 * Archivo : UdpMessage.java
 * Paquete : udp.common
 *
 * Descripcion:
 *   Envoltura JSON de los mensajes UDP y utilidades para pasarlos a
 *   DatagramPacket y de vuelta. Es el equivalente reducido de common.Message
 *   de la version TCP: misma idea de "version + type + payload", pero sin
 *   prefijo de longitud, porque en UDP cada datagrama ya es un mensaje
 *   completo con fronteras propias. Esa es justamente la diferencia de fondo
 *   entre los dos transportes.
 */

package udp.common;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Mensaje del protocolo UDP.
 */
public class UdpMessage {

    private final String type;
    private final JSONObject payload;

    private UdpMessage(String type, JSONObject payload) {
        this.type = type;
        this.payload = payload == null ? new JSONObject() : payload;
    }

    // =======================================================================
    // Construccion
    // =======================================================================

    public static UdpMessage of(String type) {
        return new UdpMessage(type, new JSONObject());
    }

    public static UdpMessage error(String code, String message) {
        return of(UdpProtocol.RES_ERROR)
                .put(UdpProtocol.P_CODE, code)
                .put(UdpProtocol.P_MESSAGE, message);
    }

    public UdpMessage put(String key, String value) {
        payload.put(key, value == null ? "" : value);
        return this;
    }

    public UdpMessage put(String key, int value) {
        payload.put(key, value);
        return this;
    }

    public UdpMessage put(String key, JSONArray value) {
        payload.put(key, value);
        return this;
    }

    // =======================================================================
    // Lectura
    // =======================================================================

    public String getType() {
        return type;
    }

    public JSONObject getPayload() {
        return payload;
    }

    public String optString(String campo, String porDefecto) {
        return payload.isNull(campo) ? porDefecto : payload.optString(campo, porDefecto);
    }

    public int optInt(String campo, int porDefecto) {
        return payload.optInt(campo, porDefecto);
    }

    // =======================================================================
    // Serializacion
    // =======================================================================

    /** Convierte el mensaje a su JSON completo. */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put(UdpProtocol.FIELD_VERSION, UdpProtocol.VERSION);
        json.put(UdpProtocol.FIELD_TYPE, type);
        json.put(UdpProtocol.FIELD_PAYLOAD, payload);
        return json;
    }

    /** Serializa a bytes UTF-8. */
    public byte[] toBytes() {
        return toJson().toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Empaqueta el mensaje en un datagrama dirigido a un destino.
     *
     * @param direccion IP del destinatario
     * @param puerto    puerto del destinatario
     */
    public DatagramPacket toPacket(InetAddress direccion, int puerto) {
        byte[] datos = toBytes();
        return new DatagramPacket(datos, datos.length, direccion, puerto);
    }

    // =======================================================================
    // Deserializacion
    // =======================================================================

    /**
     * Reconstruye un mensaje a partir de un datagrama recibido.
     *
     * @throws JSONException si el contenido no es JSON valido o falta "type"
     */
    public static UdpMessage parse(DatagramPacket paquete) {
        String texto = new String(paquete.getData(), paquete.getOffset(),
                paquete.getLength(), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(texto); // lanza JSONException si es invalido

        if (!json.has(UdpProtocol.FIELD_TYPE) || json.isNull(UdpProtocol.FIELD_TYPE)) {
            throw new JSONException("Falta el campo obligatorio 'type'");
        }
        String tipo = json.getString(UdpProtocol.FIELD_TYPE).toUpperCase();
        JSONObject payload = json.optJSONObject(UdpProtocol.FIELD_PAYLOAD);

        return new UdpMessage(tipo, payload);
    }

    /** Representacion corta para los logs, recortando los datos binarios. */
    @Override
    public String toString() {
        JSONObject copia = new JSONObject(payload.toString());
        if (copia.has(UdpProtocol.P_DATA)) {
            copia.put(UdpProtocol.P_DATA,
                    "<" + copia.optString(UdpProtocol.P_DATA, "").length() + " chars>");
        }
        return type + " " + copia;
    }
}
