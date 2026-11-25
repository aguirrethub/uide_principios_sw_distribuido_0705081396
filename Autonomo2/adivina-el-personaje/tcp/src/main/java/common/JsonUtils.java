/*
 * Archivo : JsonUtils.java
 * Paquete : common
 *
 * Descripcion:
 *   Conversion entre bytes/cadenas y objetos JSON, con validacion y errores
 *   tipados. A diferencia del codigo base, aqui NO se imprime el JSON por
 *   consola (una imagen en Base64 inundaria la terminal) y los fallos de
 *   parseo se traducen a ProtocolException con el codigo adecuado, de modo
 *   que el servidor siempre pueda responder un ERROR bien formado.
 */

package common;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Utilidades JSON del protocolo. Clase de utilidad: no se instancia.
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    /**
     * Convierte un arreglo de bytes UTF-8 en un objeto JSON.
     *
     * @throws ProtocolException con codigo BAD_JSON si el texto no es JSON valido
     */
    public static JSONObject fromByteArray(byte[] bytes) throws ProtocolException {
        if (bytes == null || bytes.length == 0) {
            throw new ProtocolException(Protocol.ERR_BAD_JSON, "Se recibio un mensaje vacio");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            String muestra = text.length() > 120 ? text.substring(0, 120) + "..." : text;
            throw new ProtocolException(Protocol.ERR_BAD_JSON,
                    "El mensaje no es JSON valido: " + muestra, e);
        }
    }

    /** Serializa un objeto JSON a bytes UTF-8. */
    public static byte[] toByteArray(JSONObject object) {
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Lee una cadena obligatoria del objeto.
     *
     * @throws ProtocolException MISSING_FIELD si no existe,
     *                           INVALID_PAYLOAD si no es texto o esta vacia
     */
    public static String requireString(JSONObject json, String field) throws ProtocolException {
        if (!json.has(field) || json.isNull(field)) {
            throw new ProtocolException(Protocol.ERR_MISSING_FIELD,
                    "Falta el campo obligatorio '" + field + "'");
        }
        Object value = json.get(field);
        if (!(value instanceof String)) {
            throw new ProtocolException(Protocol.ERR_INVALID_PAYLOAD,
                    "El campo '" + field + "' debe ser una cadena de texto");
        }
        String text = ((String) value).trim();
        if (text.isEmpty()) {
            throw new ProtocolException(Protocol.ERR_INVALID_PAYLOAD,
                    "El campo '" + field + "' no puede estar vacio");
        }
        return text;
    }

    /**
     * Lee un booleano obligatorio del objeto.
     *
     * @throws ProtocolException si no existe o no es booleano
     */
    public static boolean requireBoolean(JSONObject json, String field) throws ProtocolException {
        if (!json.has(field) || json.isNull(field)) {
            throw new ProtocolException(Protocol.ERR_MISSING_FIELD,
                    "Falta el campo obligatorio '" + field + "'");
        }
        Object value = json.get(field);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        // Tolerancia razonable: aceptar "true"/"false" como texto.
        if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase();
            if (s.equals("true") || s.equals("si") || s.equals("s") || s.equals("yes")) {
                return true;
            }
            if (s.equals("false") || s.equals("no") || s.equals("n")) {
                return false;
            }
        }
        throw new ProtocolException(Protocol.ERR_INVALID_PAYLOAD,
                "El campo '" + field + "' debe ser booleano (true/false)");
    }

    /** Devuelve una cadena opcional, o {@code porDefecto} si no esta presente. */
    public static String optString(JSONObject json, String field, String porDefecto) {
        if (json == null || !json.has(field) || json.isNull(field)) {
            return porDefecto;
        }
        return json.optString(field, porDefecto);
    }

    /** Devuelve un entero opcional, o {@code porDefecto} si no esta presente. */
    public static int optInt(JSONObject json, String field, int porDefecto) {
        if (json == null || !json.has(field) || json.isNull(field)) {
            return porDefecto;
        }
        return json.optInt(field, porDefecto);
    }

    /** Devuelve un booleano opcional, o {@code porDefecto} si no esta presente. */
    public static boolean optBoolean(JSONObject json, String field, boolean porDefecto) {
        if (json == null || !json.has(field) || json.isNull(field)) {
            return porDefecto;
        }
        return json.optBoolean(field, porDefecto);
    }
}
