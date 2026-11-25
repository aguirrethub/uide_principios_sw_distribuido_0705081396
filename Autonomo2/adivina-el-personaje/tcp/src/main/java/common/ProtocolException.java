/*
 * Archivo : ProtocolException.java
 * Paquete : common
 *
 * Descripcion:
 *   Excepcion de dominio que representa un incumplimiento del protocolo
 *   (JSON invalido, campo ausente, tipo desconocido, etc.). Lleva adjunto un
 *   codigo de error de Protocol.ERR_* para que el servidor pueda traducirla
 *   directamente a un mensaje ERROR bien formado sin perder informacion.
 */

package common;

/**
 * Error de protocolo con un codigo asociado (Protocol.ERR_*).
 */
public class ProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Codigo de error normalizado, por ejemplo Protocol.ERR_BAD_JSON. */
    private final String code;

    /**
     * @param code    codigo de Protocol.ERR_*
     * @param message mensaje legible para el usuario final
     */
    public ProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @param code    codigo de Protocol.ERR_*
     * @param message mensaje legible para el usuario final
     * @param cause   excepcion original que provoco el fallo
     */
    public ProtocolException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** @return el codigo de error normalizado. */
    public String getCode() {
        return code;
    }
}
