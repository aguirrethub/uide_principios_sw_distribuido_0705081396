/*
 * Archivo : GameSession.java
 * Paquete : server
 *
 * Descripcion:
 *   Maquina de estados de la partida de UN cliente. Aqui vive toda la logica
 *   del juego; el ClientHandler solo traduce entre sockets y esta clase.
 *
 *   ESTADOS
 *   -------
 *        CONECTADO  --HELLO-->  EN_LOBBY  --START-->  JUGANDO
 *                                  ^                     |
 *                                  |                     | (3 aciertos o 60 s)
 *                        CONTINUE(true)                  v
 *                                  +--------------- FIN_PARTIDA
 *                                                        |
 *                                              CONTINUE(false) / QUIT
 *                                                        v
 *                                                   TERMINADO
 *
 *   CONCURRENCIA
 *   ------------
 *   Dos hilos tocan esta sesion: el que atiende los comandos del cliente y el
 *   del temporizador, que puede disparar el fin de partida en cualquier
 *   momento. Por eso todos los metodos publicos que modifican estado estan
 *   sincronizados sobre el propio objeto: nunca se procesa un GUESS a la vez
 *   que expira el reloj.
 *
 *   PUNTUACION
 *   ----------
 *   Por cada frase acertada:
 *       puntos = max(10, 100 - 20*intentosFallidos - 10*pistasExtra)
 *   Saltar un personaje con NEXT cuesta 15 puntos.
 *   Al ganar se suma un bono de 2 puntos por cada segundo restante.
 *   Mas intentos => menos puntos, tal como pide el enunciado.
 */

package server;

import common.Message;
import common.Protocol;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Estado y reglas de la partida de un cliente.
 */
public class GameSession {

    /** Estados posibles de la sesion. */
    public enum Estado {
        CONECTADO, EN_LOBBY, JUGANDO, FIN_PARTIDA, TERMINADO
    }

    /** Canal de salida hacia el cliente. Lo implementa ClientHandler. */
    public interface Listener {
        /** Envia un mensaje al cliente. */
        void enviar(Message mensaje);

        /** Registra una linea en el log del servidor. */
        void log(String texto);
    }

    // --- Colaboradores -----------------------------------------------------
    private final CharacterRepository catalogo;
    private final Leaderboard marcador;
    private final ImageStore imagenes;
    private final ScheduledExecutorService temporizador;
    private final Listener listener;

    // --- Estado de la sesion ----------------------------------------------
    private Estado estado = Estado.CONECTADO;
    private String jugador = "Anonimo";

    // --- Estado de la partida en curso ------------------------------------
    private MovieCharacter actual;
    private final Set<String> personajesUsados = new HashSet<>();
    private int pistaActual;        // indice base 0 de la pista mostrada
    private int intentosFallidos;   // intentos fallidos en el personaje actual
    private int pistasExtra;        // pistas pedidas con MORE en este personaje
    private int acertadas;          // frases acertadas en la partida
    private int puntaje;            // puntaje acumulado de la partida
    private long finPartidaMs;      // instante en que se acaba el tiempo
    private ScheduledFuture<?> tareaReloj;

    public GameSession(CharacterRepository catalogo, Leaderboard marcador,
                       ImageStore imagenes, ScheduledExecutorService temporizador,
                       Listener listener) {
        this.catalogo = catalogo;
        this.marcador = marcador;
        this.imagenes = imagenes;
        this.temporizador = temporizador;
        this.listener = listener;
    }

    // =======================================================================
    // Accesores
    // =======================================================================

    public synchronized Estado getEstado() {
        return estado;
    }

    public synchronized String getJugador() {
        return jugador;
    }

    /** @return true si el cliente pidio terminar y hay que cerrar el socket. */
    public synchronized boolean terminado() {
        return estado == Estado.TERMINADO;
    }

    // =======================================================================
    // Comandos del protocolo
    // =======================================================================

    /**
     * HELLO: registra el nombre del jugador y responde WELCOME con el marcador.
     */
    public synchronized void hello(String nombre) {
        this.jugador = limpiarNombre(nombre);
        this.estado = Estado.EN_LOBBY;
        listener.log("Jugador identificado como '" + jugador + "'");

        listener.enviar(Message.of(Protocol.RES_WELCOME)
                .put(Protocol.P_NAME, jugador)
                .put(Protocol.P_MESSAGE, "¡Hola " + jugador + "! Adivina "
                        + Protocol.FRASES_PARA_GANAR + " frases en "
                        + Protocol.DURACION_PARTIDA_SEG + " segundos. "
                        + "Escribe START para comenzar.")
                .put(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR)
                .put(Protocol.P_SECONDS_LEFT, Protocol.DURACION_PARTIDA_SEG)
                .put(Protocol.P_ENTRIES, marcador.toJsonArray()));
    }

    /**
     * LEADERBOARD: envia el marcador. Valido en cualquier estado activo.
     */
    public synchronized void leaderboard() {
        listener.enviar(Message.of(Protocol.RES_LEADERBOARD)
                .put(Protocol.P_ENTRIES, marcador.toJsonArray())
                .put(Protocol.P_MESSAGE, marcador.size() == 0
                        ? "Todavía no hay partidas registradas."
                        : "Mejores " + Leaderboard.TOP + " jugadores."));
    }

    /**
     * START: arranca una partida nueva y pone en marcha el reloj de 60 s.
     */
    public synchronized void start() {
        if (estado == Estado.JUGANDO) {
            enviarError(Protocol.ERR_INVALID_STATE,
                    "Ya hay una partida en curso. Usa GUESS, MORE o NEXT.");
            return;
        }
        if (estado == Estado.CONECTADO) {
            // Tolerancia: si el cliente no dijo HELLO, se le asigna un nombre.
            jugador = "Anonimo";
        }

        acertadas = 0;
        puntaje = 0;
        personajesUsados.clear();
        estado = Estado.JUGANDO;
        finPartidaMs = System.currentTimeMillis() + Protocol.DURACION_PARTIDA_SEG * 1000L;

        programarReloj();
        nuevoPersonaje();

        listener.log("Partida iniciada para '" + jugador + "'");
        enviarPista("¡Comienza la partida! ¿Quién dijo esta frase?");
    }

    /**
     * GUESS: procesa un intento de adivinanza.
     */
    public synchronized void guess(String respuesta) {
        if (estado != Estado.JUGANDO) {
            enviarError(Protocol.ERR_INVALID_STATE,
                    "No hay una partida en curso. Envía START para comenzar.");
            return;
        }
        if (actual == null) {
            enviarError(Protocol.ERR_INTERNAL, "No hay un personaje activo.");
            return;
        }

        if (actual.matches(respuesta)) {
            int puntos = calcularPuntos();
            puntaje += puntos;
            acertadas++;
            personajesUsados.add(actual.getId());

            listener.log("'" + jugador + "' acertó " + actual.getName()
                    + " (+" + puntos + " pts, " + acertadas + "/"
                    + Protocol.FRASES_PARA_GANAR + ")");

            if (acertadas >= Protocol.FRASES_PARA_GANAR) {
                // Mensaje de acierto final y, a continuacion, fin de partida.
                listener.enviar(resultado(true, puntos,
                        "¡Correcto! Era " + actual.getName() + ". ¡Completaste las "
                        + Protocol.FRASES_PARA_GANAR + " frases!")
                        .put(Protocol.P_IMAGE, imagenes.getBase64(actual.getRevealImagePath()))
                        .put(Protocol.P_REVEAL, actual.getName())
                        .put(Protocol.P_MOVIE, actual.getMovie()));
                finalizarPartida(true);
                return;
            }

            // Quedan frases: se revela el personaje y se pasa al siguiente.
            MovieCharacter acertado = actual;
            nuevoPersonaje();
            listener.enviar(resultado(true, puntos,
                    "¡Correcto! Era " + acertado.getName() + " ("
                    + acertado.getMovie() + "). Siguiente frase:")
                    .put(Protocol.P_REVEAL, acertado.getName())
                    .put(Protocol.P_MOVIE, acertado.getMovie())
                    .put(Protocol.P_QUOTE, actual.getClue(pistaActual))
                    .put(Protocol.P_IMAGE, imagenes.getBase64(actual.getImagePath()))
                    .put(Protocol.P_IMAGE_FORMAT, "png")
                    .put(Protocol.P_CLUE_INDEX, pistaActual + 1)
                    .put(Protocol.P_CLUE_TOTAL, actual.getClueCount()));

        } else {
            intentosFallidos++;
            listener.log("'" + jugador + "' falló con '" + respuesta + "' (intento "
                    + intentosFallidos + ")");
            listener.enviar(resultado(false, 0,
                    "No es correcto. Intento nº " + intentosFallidos
                    + ". Puedes pedir otra pista con MORE o saltar con NEXT."));
        }
    }

    /**
     * MORE: entrega la siguiente pista del MISMO personaje (hasta 4).
     */
    public synchronized void more() {
        if (estado != Estado.JUGANDO) {
            enviarError(Protocol.ERR_INVALID_STATE,
                    "No hay una partida en curso. Envía START para comenzar.");
            return;
        }
        if (actual == null) {
            enviarError(Protocol.ERR_INTERNAL, "No hay un personaje activo.");
            return;
        }

        if (pistaActual + 1 >= actual.getClueCount()) {
            // Ya se mostraron las 4 pistas: no se penaliza de nuevo.
            listener.enviar(Message.of(Protocol.RES_CLUE)
                    .put(Protocol.P_MESSAGE, "Ya viste las " + actual.getClueCount()
                            + " pistas de este personaje. Adivina con GUESS o salta con NEXT.")
                    .put(Protocol.P_QUOTE, actual.getClue(pistaActual))
                    .put(Protocol.P_IMAGE, "")
                    .put(Protocol.P_CLUE_INDEX, pistaActual + 1)
                    .put(Protocol.P_CLUE_TOTAL, actual.getClueCount())
                    .put(Protocol.P_ATTEMPTS, intentosFallidos)
                    .put(Protocol.P_SOLVED, acertadas)
                    .put(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR)
                    .put(Protocol.P_SCORE, puntaje)
                    .put(Protocol.P_SECONDS_LEFT, segundosRestantes()));
            return;
        }

        pistaActual++;
        pistasExtra++;
        listener.log("'" + jugador + "' pidió la pista " + (pistaActual + 1)
                + " de " + actual.getName());
        // No se reenvia la imagen: ya la tiene el cliente y asi se ahorra ancho de banda.
        listener.enviar(Message.of(Protocol.RES_CLUE)
                .put(Protocol.P_MESSAGE, "Pista " + (pistaActual + 1) + " de "
                        + actual.getClueCount() + " (-" + Protocol.PENALIZA_PISTA + " pts):")
                .put(Protocol.P_QUOTE, actual.getClue(pistaActual))
                .put(Protocol.P_IMAGE, "")
                .put(Protocol.P_CLUE_INDEX, pistaActual + 1)
                .put(Protocol.P_CLUE_TOTAL, actual.getClueCount())
                .put(Protocol.P_ATTEMPTS, intentosFallidos)
                .put(Protocol.P_SOLVED, acertadas)
                .put(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR)
                .put(Protocol.P_SCORE, puntaje)
                .put(Protocol.P_SECONDS_LEFT, segundosRestantes()));
    }

    /**
     * NEXT: descarta el personaje actual y entrega uno nuevo, con penalizacion.
     */
    public synchronized void next() {
        if (estado != Estado.JUGANDO) {
            enviarError(Protocol.ERR_INVALID_STATE,
                    "No hay una partida en curso. Envía START para comenzar.");
            return;
        }
        puntaje = Math.max(0, puntaje - Protocol.PENALIZA_SALTO);
        if (actual != null) {
            personajesUsados.add(actual.getId());
            listener.log("'" + jugador + "' saltó a " + actual.getName()
                    + " (-" + Protocol.PENALIZA_SALTO + " pts)");
        }
        nuevoPersonaje();
        enviarPista("Nueva frase (saltar costó " + Protocol.PENALIZA_SALTO + " pts):");
    }

    /**
     * CONTINUE: respuesta a la pregunta "¿deseas seguir jugando?".
     *
     * @param otraVez true para volver al lobby, false para cerrar la conexion
     */
    public synchronized void continuar(boolean otraVez) {
        if (estado != Estado.FIN_PARTIDA) {
            enviarError(Protocol.ERR_INVALID_STATE,
                    "CONTINUE solo es válido al terminar una partida.");
            return;
        }
        if (otraVez) {
            estado = Estado.EN_LOBBY;
            listener.log("'" + jugador + "' quiere jugar otra vez");
            listener.enviar(Message.of(Protocol.RES_WELCOME)
                    .put(Protocol.P_NAME, jugador)
                    .put(Protocol.P_MESSAGE, "¡Otra partida! Envía START cuando estés listo.")
                    .put(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR)
                    .put(Protocol.P_SECONDS_LEFT, Protocol.DURACION_PARTIDA_SEG)
                    .put(Protocol.P_ENTRIES, marcador.toJsonArray()));
        } else {
            quit();
        }
    }

    /**
     * QUIT: cierre ordenado. Envia BYE y marca la sesion como terminada para
     * que el ClientHandler cierre el socket y el servidor quede libre para
     * aceptar un cliente nuevo.
     */
    public synchronized void quit() {
        cancelarReloj();
        estado = Estado.TERMINADO;
        listener.log("'" + jugador + "' cerró la sesión");
        listener.enviar(Message.of(Protocol.RES_BYE)
                .put(Protocol.P_MESSAGE, "¡Hasta la próxima, " + jugador + "! "
                        + "Gracias por jugar.")
                .put(Protocol.P_SCORE, puntaje)
                .put(Protocol.P_ENTRIES, marcador.toJsonArray()));
    }

    /** Libera los recursos de la sesion (se llama al cerrar la conexion). */
    public synchronized void cerrar() {
        cancelarReloj();
        estado = Estado.TERMINADO;
    }

    // =======================================================================
    // Temporizador
    // =======================================================================

    private void programarReloj() {
        cancelarReloj();
        tareaReloj = temporizador.schedule(
                this::tiempoAgotado, Protocol.DURACION_PARTIDA_SEG, TimeUnit.SECONDS);
    }

    private void cancelarReloj() {
        if (tareaReloj != null) {
            tareaReloj.cancel(false);
            tareaReloj = null;
        }
    }

    /**
     * Se ejecuta en el hilo del temporizador cuando se agotan los 60 segundos.
     * Esta sincronizado, asi que espera si en ese instante se esta procesando
     * un GUESS: nunca se declara derrota sobre una partida ya ganada.
     */
    private synchronized void tiempoAgotado() {
        if (estado != Estado.JUGANDO) {
            return; // la partida ya habia terminado
        }
        listener.log("Se acabó el tiempo para '" + jugador + "'");
        finalizarPartida(false);
    }

    /** Segundos que faltan para que termine la partida (nunca negativo). */
    private int segundosRestantes() {
        if (estado != Estado.JUGANDO) {
            return 0;
        }
        long resto = finPartidaMs - System.currentTimeMillis();
        return (int) Math.max(0, Math.round(resto / 1000.0));
    }

    // =======================================================================
    // Flujo interno de la partida
    // =======================================================================

    /** Selecciona un personaje nuevo y reinicia los contadores de la frase. */
    private void nuevoPersonaje() {
        actual = catalogo.aleatorio(personajesUsados);
        pistaActual = 0;
        intentosFallidos = 0;
        pistasExtra = 0;
    }

    /** Envia el mensaje CLUE con la frase y la imagen del personaje actual. */
    private void enviarPista(String mensaje) {
        if (actual == null) {
            enviarError(Protocol.ERR_INTERNAL, "No hay personajes disponibles en el servidor.");
            return;
        }
        listener.enviar(Message.of(Protocol.RES_CLUE)
                .put(Protocol.P_MESSAGE, mensaje)
                .put(Protocol.P_QUOTE, actual.getClue(pistaActual))
                .put(Protocol.P_IMAGE, imagenes.getBase64(actual.getImagePath()))
                .put(Protocol.P_IMAGE_FORMAT, "png")
                .put(Protocol.P_CLUE_INDEX, pistaActual + 1)
                .put(Protocol.P_CLUE_TOTAL, actual.getClueCount())
                .put(Protocol.P_ATTEMPTS, intentosFallidos)
                .put(Protocol.P_SOLVED, acertadas)
                .put(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR)
                .put(Protocol.P_SCORE, puntaje)
                .put(Protocol.P_SECONDS_LEFT, segundosRestantes()));
    }

    /** Construye un mensaje GUESS_RESULT con el estado comun de la partida. */
    private Message resultado(boolean correcto, int puntos, String mensaje) {
        return Message.of(Protocol.RES_GUESS_RESULT)
                .put(Protocol.P_CORRECT, correcto)
                .put(Protocol.P_POINTS_AWARDED, puntos)
                .put(Protocol.P_MESSAGE, mensaje)
                .put(Protocol.P_ATTEMPTS, intentosFallidos)
                .put(Protocol.P_SOLVED, acertadas)
                .put(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR)
                .put(Protocol.P_SCORE, puntaje)
                .put(Protocol.P_SECONDS_LEFT, segundosRestantes());
    }

    /**
     * Puntos de la frase recien acertada.
     * Mas intentos fallidos y mas pistas extra => menos puntos, con un minimo.
     */
    private int calcularPuntos() {
        int puntos = Protocol.PUNTOS_BASE
                - Protocol.PENALIZA_INTENTO * intentosFallidos
                - Protocol.PENALIZA_PISTA * pistasExtra;
        return Math.max(Protocol.PUNTOS_MINIMOS, puntos);
    }

    /**
     * Cierra la partida: calcula el bono, actualiza el marcador y envia
     * GAME_OVER con la imagen de felicitaciones o de derrota.
     */
    private void finalizarPartida(boolean gano) {
        int restantes = segundosRestantes();
        cancelarReloj();

        if (gano) {
            puntaje += restantes * Protocol.BONUS_POR_SEGUNDO;
        }
        estado = Estado.FIN_PARTIDA;

        int posicion = marcador.registrarPartida(jugador, puntaje, gano);

        String imagen = imagenes.getBase64(gano ? "img/win.png" : "img/lose.png");
        String texto = gano
                ? "¡FELICITACIONES, " + jugador + "! Adivinaste "
                  + Protocol.FRASES_PARA_GANAR + " frases con " + restantes
                  + " s de sobra (bono +" + (restantes * Protocol.BONUS_POR_SEGUNDO) + " pts)."
                : "Se acabó el tiempo. Lograste " + acertadas + " de "
                  + Protocol.FRASES_PARA_GANAR + " frases.";

        listener.log("Fin de partida de '" + jugador + "': "
                + (gano ? "VICTORIA" : "DERROTA") + " con " + puntaje
                + " pts (puesto " + posicion + ")");

        listener.enviar(Message.of(Protocol.RES_GAME_OVER)
                .put(Protocol.P_WON, gano)
                .put(Protocol.P_MESSAGE, texto)
                .put(Protocol.P_IMAGE, imagen)
                .put(Protocol.P_IMAGE_FORMAT, "png")
                .put(Protocol.P_SCORE, puntaje)
                .put(Protocol.P_SOLVED, acertadas)
                .put(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR)
                .put(Protocol.P_SECONDS_LEFT, 0)
                .put(Protocol.P_RANK, posicion)
                .put(Protocol.P_ASK_CONTINUE, true)
                .put(Protocol.P_ENTRIES, marcador.toJsonArray()));
    }

    // =======================================================================
    // Auxiliares
    // =======================================================================

    private void enviarError(String codigo, String mensaje) {
        listener.enviar(Message.error(codigo, mensaje));
    }

    private String limpiarNombre(String nombre) {
        if (nombre == null || nombre.trim().isEmpty()) {
            return "Anonimo";
        }
        String limpio = nombre.trim().replaceAll("\\s+", " ");
        return limpio.length() > 20 ? limpio.substring(0, 20) : limpio;
    }
}
