/*
 * Archivo : UdpProtocol.java
 * Paquete : udp.common
 * Proyecto: Adivina el Personaje - version reducida sobre UDP
 *
 * Descripcion:
 *   Protocolo de la version UDP. El intercambio es minimo, tal como permite el
 *   enunciado: el cliente manda su nombre y el servidor responde con una
 *   imagen.
 *
 *   EL PROBLEMA DE UDP CON LAS IMAGENES
 *   -----------------------------------
 *   Un datagrama UDP es un mensaje suelto, sin conexion, sin orden garantizado
 *   y sin retransmision. Ademas tiene un tamano maximo util y, si supera la MTU
 *   de la red (tipicamente 1500 bytes), IP lo fragmenta y basta con perder un
 *   fragmento para perder el datagrama entero. Nuestras imagenes rondan los
 *   60 KB (unos 85 KB ya en Base64), asi que NO caben en un datagrama sensato.
 *
 *   SOLUCION IMPLEMENTADA
 *   ---------------------
 *   La imagen se parte en trozos de 1200 caracteres Base64, de modo que cada
 *   datagrama quede por debajo de la MTU y no haya fragmentacion IP:
 *
 *     1. cliente -> servidor : NAME        { name }
 *     2. servidor -> cliente : IMAGE_INFO  { transferId, chunks, bytes, ... }
 *     3. servidor -> cliente : IMAGE_CHUNK { transferId, seq, data }   x N
 *     4. cliente -> servidor : RESEND      { transferId, missing: [..] }  (si falta algo)
 *     5. servidor -> cliente : IMAGE_CHUNK de los trozos pedidos
 *
 *   El paso 4 es la clave: como UDP no retransmite por su cuenta, el cliente
 *   detecta los huecos y pide solo los trozos que faltan. Es una forma sencilla
 *   de fiabilidad construida sobre un transporte no fiable.
 */

package udp.common;

/**
 * Constantes y utilidades del protocolo UDP. Clase de utilidad: no se instancia.
 */
public final class UdpProtocol {

    private UdpProtocol() {
    }

    // =======================================================================
    // Metadatos
    // =======================================================================

    public static final String VERSION = "1.0";
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 8080;

    // =======================================================================
    // Tipos de mensaje
    // =======================================================================

    /** Cliente -> servidor: envia su nombre. payload: { "name": String } */
    public static final String REQ_NAME = "NAME";

    /** Cliente -> servidor: pide trozos perdidos. payload: { transferId, missing[] } */
    public static final String REQ_RESEND = "RESEND";

    /** Servidor -> cliente: anuncia la transferencia que viene. */
    public static final String RES_IMAGE_INFO = "IMAGE_INFO";

    /** Servidor -> cliente: un trozo de la imagen. */
    public static final String RES_IMAGE_CHUNK = "IMAGE_CHUNK";

    /** Servidor -> cliente: error. payload: { code, message } */
    public static final String RES_ERROR = "ERROR";

    // =======================================================================
    // Campos
    // =======================================================================

    public static final String FIELD_VERSION = "version";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_PAYLOAD = "payload";

    public static final String P_NAME = "name";
    public static final String P_TRANSFER_ID = "transferId";
    public static final String P_CHUNKS = "chunks";
    public static final String P_SEQ = "seq";
    public static final String P_DATA = "data";
    public static final String P_BYTES = "bytes";
    public static final String P_IMAGE_FORMAT = "imageFormat";
    public static final String P_IMAGE_NAME = "imageName";
    public static final String P_MESSAGE = "message";
    public static final String P_MISSING = "missing";
    public static final String P_CODE = "code";

    // =======================================================================
    // Codigos de error
    // =======================================================================

    public static final String ERR_BAD_JSON = "BAD_JSON";
    public static final String ERR_MISSING_FIELD = "MISSING_FIELD";
    public static final String ERR_UNKNOWN_TYPE = "UNKNOWN_TYPE";
    public static final String ERR_INVALID_NAME = "INVALID_NAME";
    public static final String ERR_NO_IMAGE = "NO_IMAGE";
    public static final String ERR_UNKNOWN_TRANSFER = "UNKNOWN_TRANSFER";

    // =======================================================================
    // Parametros de transporte
    // =======================================================================

    /**
     * Caracteres Base64 que lleva cada trozo. Con 1200 el datagrama completo
     * (JSON incluido) ronda los 1,4 KB y cabe en una MTU de 1500 sin que IP
     * tenga que fragmentarlo.
     */
    public static final int CHUNK_SIZE = 1200;

    /** Tamano del buffer de recepcion de datagramas. */
    public static final int BUFFER_SIZE = 4096;

    /** Tamano del buffer del socket, para no perder rafagas de trozos. */
    public static final int SOCKET_BUFFER = 1 << 20; // 1 MB

    /** Milisegundos de espera antes de dar por perdido un trozo. */
    public static final int TIMEOUT_MS = 1500;

    /** Veces que el cliente reintenta pedir los trozos que faltan. */
    public static final int MAX_REINTENTOS = 4;

    /**
     * Pausa en microsegundos entre trozos consecutivos. Sin ella, una rafaga de
     * decenas de datagramas puede desbordar el buffer del receptor y provocar
     * perdidas evitables.
     */
    public static final int PAUSA_ENTRE_CHUNKS_US = 200;

    // =======================================================================
    // Utilidades
    // =======================================================================

    /**
     * Calcula en cuantos trozos se parte una cadena Base64.
     *
     * @param longitud numero de caracteres de la cadena
     */
    public static int numeroDeChunks(int longitud) {
        if (longitud <= 0) {
            return 0;
        }
        return (longitud + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }

    /**
     * Extrae el trozo numero {@code seq} de una cadena Base64.
     *
     * @param base64 cadena completa
     * @param seq    indice del trozo, base 0
     * @return el fragmento correspondiente, o cadena vacia si seq esta fuera de rango
     */
    public static String chunk(String base64, int seq) {
        int inicio = seq * CHUNK_SIZE;
        if (inicio >= base64.length() || seq < 0) {
            return "";
        }
        int fin = Math.min(inicio + CHUNK_SIZE, base64.length());
        return base64.substring(inicio, fin);
    }
}
