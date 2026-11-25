/*
 * Archivo : Protocol.java
 * Paquete : common
 * Proyecto: Adivina el Personaje (Principios de Sistemas de Software Distribuidos)
 *
 * Descripcion:
 *   Define TODAS las constantes del protocolo de aplicacion: tipos de mensaje,
 *   nombres de campos y codigos de error. Al centralizarlas aqui se evita el uso
 *   de "strings magicos" repartidos por el codigo, de modo que cliente y servidor
 *   siempre hablan exactamente el mismo idioma.
 *
 *   Formato de un mensaje (envoltura comun):
 *
 *     {
 *       "version"  : "1.0",
 *       "type"     : "<TIPO>",
 *       "timestamp": 1731350000000,
 *       "payload"  : { ... contenido dependiente del tipo ... }
 *     }
 *
 *   Transporte: cada mensaje se envia sobre TCP precedido por 4 bytes
 *   big-endian con su longitud (ver NetworkUtils). Esto resuelve el problema
 *   de que TCP es un flujo de bytes sin fronteras de mensaje.
 */

package common;

/**
 * Constantes del protocolo de aplicacion compartidas por cliente y servidor.
 * Clase de utilidad: no se instancia.
 */
public final class Protocol {

    private Protocol() {
        // Clase de constantes: constructor privado para impedir instanciacion.
    }

    // =======================================================================
    // Metadatos del protocolo
    // =======================================================================

    /** Version del protocolo. El servidor rechaza versiones mayores desconocidas. */
    public static final String VERSION = "1.0";

    /** Host por defecto exigido por el enunciado. */
    public static final String DEFAULT_HOST = "localhost";

    /** Puerto por defecto exigido por el enunciado. */
    public static final int DEFAULT_PORT = 8080;

    // =======================================================================
    // Campos de la envoltura
    // =======================================================================

    public static final String FIELD_VERSION = "version";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_TIMESTAMP = "timestamp";
    public static final String FIELD_PAYLOAD = "payload";

    // =======================================================================
    // Tipos de mensaje: CLIENTE -> SERVIDOR (peticiones)
    // =======================================================================

    /** Presentacion del jugador. payload: { "name": String } */
    public static final String REQ_HELLO = "HELLO";

    /** Pide el marcador. payload: {} */
    public static final String REQ_LEADERBOARD = "LEADERBOARD";

    /** Pide iniciar una partida nueva. payload: {} */
    public static final String REQ_START = "START";

    /** Envia un intento de respuesta. payload: { "answer": String } */
    public static final String REQ_GUESS = "GUESS";

    /** Pide otra pista del MISMO personaje. payload: {} */
    public static final String REQ_MORE = "MORE";

    /** Salta a un personaje nuevo (penaliza). payload: {} */
    public static final String REQ_NEXT = "NEXT";

    /** Responde si desea seguir jugando. payload: { "again": boolean } */
    public static final String REQ_CONTINUE = "CONTINUE";

    /** Cierra la sesion de forma ordenada. payload: {} */
    public static final String REQ_QUIT = "QUIT";

    // =======================================================================
    // Tipos de mensaje: SERVIDOR -> CLIENTE (respuestas y notificaciones)
    // =======================================================================

    /** Saludo inicial + marcador. */
    public static final String RES_WELCOME = "WELCOME";

    /** Entrega una pista (frase) y la imagen del personaje. */
    public static final String RES_CLUE = "CLUE";

    /** Resultado de un intento de adivinanza. */
    public static final String RES_GUESS_RESULT = "GUESS_RESULT";

    /** Fin de partida: victoria o derrota, con imagen y puntaje. */
    public static final String RES_GAME_OVER = "GAME_OVER";

    /** Marcador (leaderboard). */
    public static final String RES_LEADERBOARD = "LEADERBOARD";

    /** Despedida: el servidor cerrara la conexion tras enviarlo. */
    public static final String RES_BYE = "BYE";

    /** Error de protocolo o de aplicacion. */
    public static final String RES_ERROR = "ERROR";

    // =======================================================================
    // Campos de payload (respuestas)
    // =======================================================================

    public static final String P_NAME = "name";
    public static final String P_MESSAGE = "message";
    public static final String P_ANSWER = "answer";
    public static final String P_AGAIN = "again";

    public static final String P_QUOTE = "quote";
    public static final String P_IMAGE = "image";          // PNG en Base64
    public static final String P_IMAGE_FORMAT = "imageFormat";
    public static final String P_CLUE_INDEX = "clueIndex";  // pista actual (1..4)
    public static final String P_CLUE_TOTAL = "clueTotal";  // siempre 4
    public static final String P_ATTEMPTS = "attempts";     // intentos en la frase actual
    public static final String P_SOLVED = "solved";         // frases acertadas
    public static final String P_TARGET = "target";         // frases necesarias (3)
    public static final String P_SECONDS_LEFT = "secondsLeft";
    public static final String P_SCORE = "score";
    public static final String P_POINTS_AWARDED = "pointsAwarded";
    public static final String P_CORRECT = "correct";
    public static final String P_WON = "won";
    public static final String P_REVEAL = "reveal";         // nombre real del personaje
    public static final String P_MOVIE = "movie";
    public static final String P_ASK_CONTINUE = "askContinue";
    public static final String P_ENTRIES = "entries";       // lista del marcador
    public static final String P_RANK = "rank";
    public static final String P_WINS = "wins";
    public static final String P_GAMES = "games";
    public static final String P_DATE = "date";
    public static final String P_CODE = "code";             // codigo de error

    // =======================================================================
    // Codigos de error (payload.code en mensajes ERROR)
    // =======================================================================

    /** El texto recibido no es JSON valido. */
    public static final String ERR_BAD_JSON = "BAD_JSON";

    /** Falta un campo obligatorio de la envoltura o del payload. */
    public static final String ERR_MISSING_FIELD = "MISSING_FIELD";

    /** El tipo de mensaje no existe en el protocolo. */
    public static final String ERR_UNKNOWN_TYPE = "UNKNOWN_TYPE";

    /** Version de protocolo incompatible. */
    public static final String ERR_BAD_VERSION = "BAD_VERSION";

    /** El comando es valido pero no se puede ejecutar en el estado actual. */
    public static final String ERR_INVALID_STATE = "INVALID_STATE";

    /** El nombre del jugador esta vacio o es invalido. */
    public static final String ERR_INVALID_NAME = "INVALID_NAME";

    /** Un campo tiene el tipo de dato equivocado. */
    public static final String ERR_INVALID_PAYLOAD = "INVALID_PAYLOAD";

    /** Fallo inesperado en el servidor. */
    public static final String ERR_INTERNAL = "INTERNAL_ERROR";

    // =======================================================================
    // Reglas del juego (compartidas para que el cliente pueda mostrarlas)
    // =======================================================================

    /** Frases distintas que hay que acertar para ganar. */
    public static final int FRASES_PARA_GANAR = 3;

    /** Duracion de la partida en segundos. */
    public static final int DURACION_PARTIDA_SEG = 60;

    /** Pistas disponibles por personaje. */
    public static final int PISTAS_POR_PERSONAJE = 4;

    /** Puntaje base al acertar una frase al primer intento y sin pistas extra. */
    public static final int PUNTOS_BASE = 100;

    /** Penalizacion por cada intento fallido en la frase actual. */
    public static final int PENALIZA_INTENTO = 20;

    /** Penalizacion por cada pista extra solicitada con MORE. */
    public static final int PENALIZA_PISTA = 10;

    /** Penalizacion fija por saltar un personaje con NEXT. */
    public static final int PENALIZA_SALTO = 15;

    /** Puntaje minimo garantizado por una frase acertada. */
    public static final int PUNTOS_MINIMOS = 10;

    /** Puntos extra por cada segundo restante al ganar. */
    public static final int BONUS_POR_SEGUNDO = 2;

    /** Tamano maximo aceptado para un mensaje entrante (proteccion anti-abuso). */
    public static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024; // 8 MB
}
