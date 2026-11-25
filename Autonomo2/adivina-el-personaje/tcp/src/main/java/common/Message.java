/*
 * Archivo : Message.java
 * Paquete : common
 *
 * Descripcion:
 *   Representa la envoltura comun de TODO mensaje del protocolo y concentra su
 *   construccion y validacion. Gracias a esta clase, ni el cliente ni el
 *   servidor manipulan directamente la estructura JSON: piden
 *   Message.of(TIPO).put(campo, valor) y la envoltura se arma sola.
 *
 *   Estructura generada:
 *     {
 *       "version"  : "1.0",
 *       "type"     : "CLUE",
 *       "timestamp": 1731350000000,
 *       "payload"  : { ... }
 *     }
 */

package common;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Envoltura de un mensaje del protocolo (version + tipo + timestamp + payload).
 */
public class Message {

    private final String version;
    private final String type;
    private final long timestamp;
    private final JSONObject payload;

    // =======================================================================
    // Construccion
    // =======================================================================

    private Message(String version, String type, long timestamp, JSONObject payload) {
        this.version = version;
        this.type = type;
        this.timestamp = timestamp;
        this.payload = payload == null ? new JSONObject() : payload;
    }

    /**
     * Crea un mensaje nuevo del tipo indicado, con payload vacio.
     * Los campos se agregan encadenando llamadas a {@link #put}.
     */
    public static Message of(String type) {
        return new Message(Protocol.VERSION, type, System.currentTimeMillis(), new JSONObject());
    }

    /** Crea un mensaje ERROR con codigo y descripcion. */
    public static Message error(String code, String description) {
        return of(Protocol.RES_ERROR)
                .put(Protocol.P_CODE, code)
                .put(Protocol.P_MESSAGE, description);
    }

    /** Crea un mensaje ERROR a partir de una ProtocolException. */
    public static Message error(ProtocolException e) {
        return error(e.getCode(), e.getMessage());
    }

    // =======================================================================
    // Escritura del payload (API encadenable)
    // =======================================================================

    public Message put(String key, String value) {
        payload.put(key, value == null ? JSONObject.NULL : value);
        return this;
    }

    public Message put(String key, int value) {
        payload.put(key, value);
        return this;
    }

    public Message put(String key, long value) {
        payload.put(key, value);
        return this;
    }

    public Message put(String key, boolean value) {
        payload.put(key, value);
        return this;
    }

    public Message put(String key, JSONArray value) {
        payload.put(key, value == null ? new JSONArray() : value);
        return this;
    }

    public Message put(String key, JSONObject value) {
        payload.put(key, value == null ? new JSONObject() : value);
        return this;
    }

    // =======================================================================
    // Lectura
    // =======================================================================

    public String getType() {
        return type;
    }

    public String getVersion() {
        return version;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /** @return el payload (nunca nulo). */
    public JSONObject getPayload() {
        return payload;
    }

    // Atajos de lectura con validacion, delegando en JsonUtils.

    public String requireString(String field) throws ProtocolException {
        return JsonUtils.requireString(payload, field);
    }

    public boolean requireBoolean(String field) throws ProtocolException {
        return JsonUtils.requireBoolean(payload, field);
    }

    public String optString(String field, String porDefecto) {
        return JsonUtils.optString(payload, field, porDefecto);
    }

    public int optInt(String field, int porDefecto) {
        return JsonUtils.optInt(payload, field, porDefecto);
    }

    public boolean optBoolean(String field, boolean porDefecto) {
        return JsonUtils.optBoolean(payload, field, porDefecto);
    }

    // =======================================================================
    // Serializacion
    // =======================================================================

    /** Convierte el mensaje a su representacion JSON completa. */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put(Protocol.FIELD_VERSION, version);
        json.put(Protocol.FIELD_TYPE, type);
        json.put(Protocol.FIELD_TIMESTAMP, timestamp);
        json.put(Protocol.FIELD_PAYLOAD, payload);
        return json;
    }

    /** Serializa el mensaje a bytes UTF-8 listos para enviar por el socket. */
    public byte[] toBytes() {
        return JsonUtils.toByteArray(toJson());
    }

    // =======================================================================
    // Deserializacion y validacion
    // =======================================================================

    /**
     * Reconstruye y valida un mensaje a partir de los bytes recibidos.
     *
     * Validaciones aplicadas, en orden:
     *   1. que el contenido sea JSON valido           -> BAD_JSON
     *   2. que exista el campo "type"                 -> MISSING_FIELD
     *   3. que la version mayor sea compatible        -> BAD_VERSION
     *   4. que "payload", si viene, sea un objeto     -> INVALID_PAYLOAD
     *
     * @throws ProtocolException con el codigo correspondiente
     */
    public static Message parse(byte[] bytes) throws ProtocolException {
        JSONObject json = JsonUtils.fromByteArray(bytes);

        String type = JsonUtils.requireString(json, Protocol.FIELD_TYPE)
                .toUpperCase();

        String version = JsonUtils.optString(json, Protocol.FIELD_VERSION, Protocol.VERSION);
        verificarVersion(version);

        long timestamp = json.optLong(Protocol.FIELD_TIMESTAMP, System.currentTimeMillis());

        JSONObject payload;
        if (!json.has(Protocol.FIELD_PAYLOAD) || json.isNull(Protocol.FIELD_PAYLOAD)) {
            // Un payload ausente se interpreta como payload vacio: es valido
            // para comandos sin datos como MORE, NEXT o QUIT.
            payload = new JSONObject();
        } else {
            try {
                payload = json.getJSONObject(Protocol.FIELD_PAYLOAD);
            } catch (JSONException e) {
                throw new ProtocolException(Protocol.ERR_INVALID_PAYLOAD,
                        "El campo 'payload' debe ser un objeto JSON", e);
            }
        }

        return new Message(version, type, timestamp, payload);
    }

    /**
     * Acepta cualquier version con la misma version mayor (1.x).
     * Una version mayor distinta se rechaza con BAD_VERSION.
     */
    private static void verificarVersion(String version) throws ProtocolException {
        String mayorRecibida = version.split("\\.")[0];
        String mayorEsperada = Protocol.VERSION.split("\\.")[0];
        if (!mayorRecibida.equals(mayorEsperada)) {
            throw new ProtocolException(Protocol.ERR_BAD_VERSION,
                    "Version de protocolo incompatible: se recibio '" + version
                    + "' y el servidor habla '" + Protocol.VERSION + "'");
        }
    }

    // =======================================================================
    // Depuracion
    // =======================================================================

    /**
     * Representacion corta para los logs: omite el contenido de las imagenes
     * para que la consola siga siendo legible.
     */
    @Override
    public String toString() {
        JSONObject copia = new JSONObject(payload.toString());
        if (copia.has(Protocol.P_IMAGE) && !copia.isNull(Protocol.P_IMAGE)) {
            int largo = copia.optString(Protocol.P_IMAGE, "").length();
            copia.put(Protocol.P_IMAGE, "<base64 " + largo + " bytes>");
        }
        return type + " " + copia;
    }
}
