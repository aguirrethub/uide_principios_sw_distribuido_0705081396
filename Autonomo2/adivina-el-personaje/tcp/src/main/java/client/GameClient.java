/*
 * Archivo : GameClient.java
 * Paquete : client
 *
 * Descripcion:
 *   Punto de entrada del cliente TCP. Hace de puente entre tres piezas:
 *
 *     ClientGui (interfaz)  <--->  GameClient  <--->  ServerConnection (red)
 *
 *   - Implementa ClientGui.Acciones: convierte los clics del usuario en
 *     mensajes JSON del protocolo.
 *   - Implementa ServerConnection.Listener: recibe los mensajes del servidor
 *     (incluidos los que llegan solos, como el GAME_OVER del temporizador) y
 *     actualiza la interfaz.
 *
 *   Tiene dos modos:
 *     - grafico (por defecto)
 *     - consola, con --nogui, util para probar contra un servidor remoto por
 *       SSH donde no hay entorno grafico.
 *
 * Uso:
 *   gradle :tcp:runClient
 *   gradle :tcp:runClient -Phost=localhost -Pport=8080
 *   gradle :tcp:runClientConsola
 *   java client.GameClient [host] [puerto] [--nogui]
 */

package client;

import client.gui.ClientGui;
import client.gui.GridMaker;
import client.gui.OutputPanel;
import common.Message;
import common.Protocol;
import common.ProtocolException;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cliente del juego "Adivina el Personaje".
 */
public class GameClient implements ClientGui.Acciones, ServerConnection.Listener {

    /**
     * Milisegundos que se espera antes de preguntar "¿jugar otra vez?", para
     * que la imagen de victoria o derrota se vea antes de abrir el diálogo.
     */
    private static final int PAUSA_ANTES_DEL_DIALOGO_MS = 2200;

    private final String host;
    private final int puerto;
    private final boolean modoConsola;

    private ServerConnection conexion;
    private ClientGui ventana;
    private String nombreJugador = "Jugador";

    /** Ultimo numero de pista recibido: define cuantas baldosas se descubren. */
    private int pistaActual = 1;

    /** true mientras se espera la respuesta a "¿jugar otra vez?". */
    private volatile boolean esperandoContinuar = false;

    /**
     * true cuando ya llegó el BYE del servidor. Sirve para distinguir un
     * cierre ordenado (no hay que alarmar al usuario) de una caída de red.
     */
    private volatile boolean despedidaRecibida = false;

    public GameClient(String host, int puerto, boolean modoConsola) {
        this.host = host;
        this.puerto = puerto;
        this.modoConsola = modoConsola;
    }

    // =======================================================================
    // Arranque
    // =======================================================================

    /** Arranca el cliente en el modo correspondiente. */
    public void iniciar() {
        if (modoConsola) {
            iniciarConsola();
        } else {
            iniciarGrafico();
        }
    }

    // ----------------------------------------------------------------------
    // Modo grafico
    // ----------------------------------------------------------------------

    private void iniciarGrafico() {
        aplicarLookAndFeel();

        SwingUtilities.invokeLater(() -> {
            ventana = new ClientGui("Adivina el Personaje  -  cliente TCP", this);
            ventana.setVisible(true);

            ventana.salida().titulo("ADIVINA EL PERSONAJE");
            ventana.salida().sistema("Reglas: adivina " + Protocol.FRASES_PARA_GANAR
                    + " frases distintas en " + Protocol.DURACION_PARTIDA_SEG + " segundos.");
            ventana.salida().sistema("Cada personaje tiene " + Protocol.PISTAS_POR_PERSONAJE
                    + " pistas. Pedir pistas o fallar resta puntos.");
            ventana.limpiarImagen("Conectando con el servidor...");

            nombreJugador = ventana.pedirNombre(nombreJugador);
            ventana.setJugador(nombreJugador);

            // La conexion se hace fuera del EDT para no congelar la ventana.
            new Thread(this::conectarYSaludar, "conectar").start();
        });
    }

    private void conectarYSaludar() {
        ventana.salida().sistema("Conectando a " + host + ":" + puerto + " ...");
        conexion = new ServerConnection(host, puerto, this);
        try {
            conexion.conectar();
            ventana.setConexion("conectado a " + host + ":" + puerto, true);
            ventana.salida().sistema("Conexión establecida.");
            conexion.enviar(Message.of(Protocol.REQ_HELLO).put(Protocol.P_NAME, nombreJugador));
        } catch (IOException e) {
            ventana.setConexion("sin conexión", false);
            ventana.salida().error("No se pudo conectar a " + host + ":" + puerto
                    + " -> " + e.getMessage());
            ventana.limpiarImagen("Sin conexión con el servidor.\n"
                    + "Verifica que el servidor esté en marcha.");
            ventana.aviso("Sin conexión",
                    "No se pudo conectar a " + host + ":" + puerto + ".\n\n"
                    + e.getMessage() + "\n\n"
                    + "Inicia el servidor con:\n"
                    + "   gradle :tcp:runServer -Pport=" + puerto);
        }
    }

    private void aplicarLookAndFeel() {
        // Los diálogos estándar de Swing (Aceptar/Cancelar) toman sus textos
        // del idioma por omisión; se fija español para que no salgan en inglés
        // en un equipo configurado en otro idioma.
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es"));
        javax.swing.JComponent.setDefaultLocale(java.util.Locale.forLanguageTag("es"));
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    return;
                }
            }
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Si falla, se usa el aspecto por defecto: no es motivo para abortar.
            System.err.println("[GameClient] No se pudo aplicar el Look&Feel: "
                    + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------
    // Modo consola
    // ----------------------------------------------------------------------

    private void iniciarConsola() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("   ADIVINA EL PERSONAJE  -  CLIENTE (modo consola)");
        System.out.println("=================================================================");

        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        try {
            System.out.print("Tu nombre: ");
            String linea = stdin.readLine();
            if (linea != null && !linea.trim().isEmpty()) {
                nombreJugador = linea.trim();
            }

            conexion = new ServerConnection(host, puerto, this);
            System.out.println("Conectando a " + host + ":" + puerto + " ...");
            conexion.conectar();
            System.out.println("Conectado.");
            conexion.enviar(Message.of(Protocol.REQ_HELLO).put(Protocol.P_NAME, nombreJugador));

            menuConsola(stdin);

        } catch (IOException e) {
            System.err.println("ERROR: no se pudo conectar a " + host + ":" + puerto
                    + " -> " + e.getMessage());
            System.err.println("Inicia el servidor con: gradle :tcp:runServer -Pport=" + puerto);
            System.exit(3);
        }
    }

    private void menuConsola(BufferedReader stdin) throws IOException {
        imprimirAyudaConsola();
        String linea;
        while (conexion.estaConectado() && (linea = stdin.readLine()) != null) {
            String entrada = linea.trim();
            if (entrada.isEmpty()) {
                continue;
            }
            String comando = entrada.split("\\s+")[0].toLowerCase();
            String resto = entrada.length() > comando.length()
                    ? entrada.substring(comando.length()).trim() : "";

            switch (comando) {
                case "start":   iniciarPartida(); break;
                case "guess":   adivinar(resto); break;
                case "more":    pedirPista(); break;
                case "next":    siguientePersonaje(); break;
                case "board":
                case "leaderboard": pedirMarcador(); break;
                case "si":
                case "yes":     responderContinuar(true); break;
                case "no":      responderContinuar(false); break;
                case "quit":
                case "salir":   salir(); return;
                case "help":
                case "ayuda":   imprimirAyudaConsola(); break;
                default:
                    // Si no es un comando conocido, se interpreta como respuesta.
                    adivinar(entrada);
                    break;
            }
        }
    }

    private void imprimirAyudaConsola() {
        System.out.println();
        System.out.println("Comandos: start | guess <nombre> | more | next | board | quit");
        System.out.println("          (si escribes algo que no es un comando, se envía como respuesta)");
        System.out.println("          al terminar una partida responde: si / no");
        System.out.println();
    }

    private void responderContinuar(boolean otraVez) {
        if (!esperandoContinuar) {
            System.out.println("(No hay ninguna pregunta pendiente)");
            return;
        }
        esperandoContinuar = false;
        conexion.enviar(Message.of(Protocol.REQ_CONTINUE).put(Protocol.P_AGAIN, otraVez));
    }

    // =======================================================================
    // ClientGui.Acciones  (el usuario actúa -> se envía al servidor)
    // =======================================================================

    @Override
    public void iniciarPartida() {
        if (conexion != null) {
            conexion.enviar(Message.of(Protocol.REQ_START));
        }
    }

    @Override
    public void adivinar(String respuesta) {
        if (conexion == null) {
            return;
        }
        if (respuesta == null || respuesta.trim().isEmpty()) {
            escribir("Escribe un nombre antes de responder.", OutputPanel.Tipo.FALLO);
            return;
        }
        conexion.enviar(Message.of(Protocol.REQ_GUESS)
                .put(Protocol.P_ANSWER, respuesta.trim()));
    }

    @Override
    public void pedirPista() {
        if (conexion != null) {
            conexion.enviar(Message.of(Protocol.REQ_MORE));
        }
    }

    @Override
    public void siguientePersonaje() {
        if (conexion != null) {
            conexion.enviar(Message.of(Protocol.REQ_NEXT));
        }
    }

    @Override
    public void pedirMarcador() {
        if (conexion != null) {
            conexion.enviar(Message.of(Protocol.REQ_LEADERBOARD));
        }
    }

    @Override
    public void salir() {
        if (conexion != null && conexion.estaConectado()) {
            conexion.enviar(Message.of(Protocol.REQ_QUIT));
            // Se espera un instante a que llegue el BYE antes de cerrar.
            new Thread(() -> {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                cerrarTodo();
            }, "salida").start();
        } else {
            cerrarTodo();
        }
    }

    private void cerrarTodo() {
        if (conexion != null) {
            conexion.cerrar();
        }
        if (ventana != null) {
            ventana.dispose();
        }
        System.exit(0);
    }

    // =======================================================================
    // ServerConnection.Listener  (llega algo del servidor -> se muestra)
    // =======================================================================

    @Override
    public void onMessage(Message mensaje) {
        JSONObject p = mensaje.getPayload();

        switch (mensaje.getType()) {
            case Protocol.RES_WELCOME:    manejarWelcome(p); break;
            case Protocol.RES_CLUE:       manejarClue(p); break;
            case Protocol.RES_GUESS_RESULT: manejarResultado(p); break;
            case Protocol.RES_GAME_OVER:  manejarFinPartida(p); break;
            case Protocol.RES_LEADERBOARD: manejarMarcador(p); break;
            case Protocol.RES_BYE:        manejarBye(p); break;
            case Protocol.RES_ERROR:      manejarError(p); break;
            default:
                escribir("Mensaje no reconocido del servidor: " + mensaje.getType(),
                        OutputPanel.Tipo.ERROR);
                break;
        }
    }

    private void manejarWelcome(JSONObject p) {
        String mensaje = p.optString(Protocol.P_MESSAGE, "Bienvenido.");
        escribir(mensaje, OutputPanel.Tipo.TITULO);

        if (ventana != null) {
            ventana.setJugador(p.optString(Protocol.P_NAME, nombreJugador));
            ventana.estadoLobby();
            ventana.setFrase("Pulsa INICIAR para comenzar la partida.");
            ventana.limpiarImagen("Listo para jugar.\nPulsa INICIAR.");
            ventana.setEstado(0, p.optInt(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR),
                    0, Protocol.DURACION_PARTIDA_SEG);
        }
        mostrarMarcadorEnTexto(p.optJSONArray(Protocol.P_ENTRIES));
    }

    private void manejarClue(JSONObject p) {
        String mensaje = p.optString(Protocol.P_MESSAGE, "");
        String frase = p.optString(Protocol.P_QUOTE, "");
        int indicePista = p.optInt(Protocol.P_CLUE_INDEX, 1);
        int totalPistas = p.optInt(Protocol.P_CLUE_TOTAL, Protocol.PISTAS_POR_PERSONAJE);
        pistaActual = indicePista;

        if (!mensaje.isEmpty()) {
            escribir(mensaje, OutputPanel.Tipo.INFO);
        }
        escribir("Pista " + indicePista + "/" + totalPistas + ": \"" + frase + "\"",
                OutputPanel.Tipo.PISTA);

        if (ventana != null) {
            ventana.setFrase("“" + frase + "”");
            actualizarEstado(p);
            ventana.estadoJugando();

            String imagen = p.optString(Protocol.P_IMAGE, "");
            if (!imagen.isEmpty()) {
                // Personaje nuevo: se carga la imagen oculta por baldosas.
                BufferedImage img = GridMaker.fromBase64(imagen);
                ventana.mostrarImagenOculta(img, indicePista);
            } else {
                // Misma imagen, otra pista: se descubre una baldosa mas.
                ventana.revelarBaldosas(indicePista);
            }
        }
    }

    private void manejarResultado(JSONObject p) {
        boolean correcto = p.optBoolean(Protocol.P_CORRECT, false);
        String mensaje = p.optString(Protocol.P_MESSAGE, "");
        int puntos = p.optInt(Protocol.P_POINTS_AWARDED, 0);

        if (correcto) {
            escribir(mensaje + (puntos > 0 ? "  (+" + puntos + " pts)" : ""),
                    OutputPanel.Tipo.ACIERTO);
        } else {
            escribir(mensaje, OutputPanel.Tipo.FALLO);
        }

        String imagen = p.optString(Protocol.P_IMAGE, "");
        String frase = p.optString(Protocol.P_QUOTE, "");

        // La frase siguiente se muestra siempre, también en modo consola.
        if (correcto && !frase.isEmpty()) {
            pistaActual = p.optInt(Protocol.P_CLUE_INDEX, 1);
            escribir("Nueva frase: \"" + frase + "\"", OutputPanel.Tipo.PISTA);
        }

        if (ventana == null) {
            return;
        }
        actualizarEstado(p);

        if (correcto && !frase.isEmpty()) {
            // Acierto intermedio: llega ya la frase y la imagen del siguiente.
            ventana.setFrase("“" + frase + "”");
            BufferedImage img = GridMaker.fromBase64(imagen);
            if (img != null) {
                ventana.mostrarImagenOculta(img, pistaActual);
            }
        } else if (correcto && !imagen.isEmpty()) {
            // Acierto final: se revela el personaje antes del GAME_OVER.
            ventana.mostrarImagenCompleta(GridMaker.fromBase64(imagen));
        }
    }

    private void manejarFinPartida(JSONObject p) {
        boolean gano = p.optBoolean(Protocol.P_WON, false);
        String mensaje = p.optString(Protocol.P_MESSAGE, "");
        int puntaje = p.optInt(Protocol.P_SCORE, 0);
        int puesto = p.optInt(Protocol.P_RANK, 0);

        escribir("", OutputPanel.Tipo.INFO);
        escribir(gano ? "*** VICTORIA ***" : "*** DERROTA ***",
                gano ? OutputPanel.Tipo.ACIERTO : OutputPanel.Tipo.FALLO);
        escribir(mensaje, OutputPanel.Tipo.INFO);
        escribir("Puntaje final: " + puntaje + " pts"
                + (puesto > 0 ? "   ·   Puesto " + puesto + " del marcador" : ""),
                OutputPanel.Tipo.TITULO);

        mostrarMarcadorEnTexto(p.optJSONArray(Protocol.P_ENTRIES));

        if (ventana != null) {
            ventana.estadoFinPartida();
            ventana.setEstado(p.optInt(Protocol.P_SOLVED, 0),
                    p.optInt(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR),
                    puntaje, 0);
            ventana.setFrase(gano ? "¡Ganaste! " + puntaje + " puntos."
                                  : "Se acabó el tiempo. " + puntaje + " puntos.");
            ventana.mostrarImagenCompleta(
                    GridMaker.fromBase64(p.optString(Protocol.P_IMAGE, "")));

            // La pregunta "¿jugar otra vez?" se retrasa un momento para que el
            // jugador alcance a ver la imagen de felicitaciones o de derrota
            // antes de que el diálogo la tape.
            if (p.optBoolean(Protocol.P_ASK_CONTINUE, true)) {
                javax.swing.Timer retardo = new javax.swing.Timer(
                        PAUSA_ANTES_DEL_DIALOGO_MS, e -> {
                            boolean otraVez = ventana.preguntarContinuar(
                                    mensaje + "\nPuntaje: " + puntaje + " pts");
                            conexion.enviar(Message.of(Protocol.REQ_CONTINUE)
                                    .put(Protocol.P_AGAIN, otraVez));
                        });
                retardo.setRepeats(false);
                SwingUtilities.invokeLater(retardo::start);
            }
        } else if (p.optBoolean(Protocol.P_ASK_CONTINUE, true)) {
            esperandoContinuar = true;
            System.out.println(">>> ¿Deseas jugar otra partida? Escribe 'si' o 'no'.");
        }
    }

    private void manejarMarcador(JSONObject p) {
        JSONArray entradas = p.optJSONArray(Protocol.P_ENTRIES);
        String mensaje = p.optString(Protocol.P_MESSAGE, "");
        if (!mensaje.isEmpty()) {
            escribir(mensaje, OutputPanel.Tipo.SISTEMA);
        }
        mostrarMarcadorEnTexto(entradas);

        if (ventana != null && entradas != null) {
            Object[][] filas = new Object[entradas.length()][5];
            for (int i = 0; i < entradas.length(); i++) {
                JSONObject e = entradas.getJSONObject(i);
                filas[i][0] = e.optInt(Protocol.P_RANK, i + 1);
                filas[i][1] = e.optString(Protocol.P_NAME, "?");
                filas[i][2] = e.optInt(Protocol.P_SCORE, 0);
                filas[i][3] = e.optInt(Protocol.P_WINS, 0) + " / "
                        + e.optInt(Protocol.P_GAMES, 0);
                filas[i][4] = e.optString(Protocol.P_DATE, "");
            }
            ventana.mostrarMarcador(filas);
        }
    }

    private void manejarBye(JSONObject p) {
        despedidaRecibida = true;
        escribir(p.optString(Protocol.P_MESSAGE, "Hasta luego."), OutputPanel.Tipo.TITULO);
        if (ventana != null) {
            ventana.setConexion("desconectado", false);
            ventana.estadoDesconectado();
        }
        if (modoConsola) {
            System.out.println("Conexión finalizada.");
        }
    }

    private void manejarError(JSONObject p) {
        String codigo = p.optString(Protocol.P_CODE, "ERROR");
        String mensaje = p.optString(Protocol.P_MESSAGE, "Error desconocido");
        escribir("[" + codigo + "] " + mensaje, OutputPanel.Tipo.ERROR);
    }

    @Override
    public void onProtocolError(ProtocolException e) {
        escribir("Respuesta ilegible del servidor [" + e.getCode() + "]: "
                + e.getMessage(), OutputPanel.Tipo.ERROR);
    }

    @Override
    public void onDisconnected(String motivo) {
        // Tras un BYE el cierre es el esperado: no se reporta como error.
        if (despedidaRecibida) {
            escribir("Conexión cerrada correctamente.", OutputPanel.Tipo.SISTEMA);
        } else {
            escribir("Desconectado: " + motivo, OutputPanel.Tipo.ERROR);
        }
        if (ventana != null) {
            ventana.setConexion("desconectado", false);
            ventana.estadoDesconectado();
            ventana.limpiarImagen(despedidaRecibida
                    ? "Sesión finalizada.\n¡Gracias por jugar!"
                    : "Se perdió la conexión con el servidor.");
        }
        if (modoConsola) {
            System.exit(0);
        }
    }

    // =======================================================================
    // Auxiliares
    // =======================================================================

    private void actualizarEstado(JSONObject p) {
        if (ventana == null) {
            return;
        }
        ventana.setEstado(
                p.optInt(Protocol.P_SOLVED, 0),
                p.optInt(Protocol.P_TARGET, Protocol.FRASES_PARA_GANAR),
                p.optInt(Protocol.P_SCORE, 0),
                p.optInt(Protocol.P_SECONDS_LEFT, 0));
    }

    /** Escribe en la consola de la ventana o en la salida estándar. */
    private void escribir(String texto, OutputPanel.Tipo tipo) {
        if (ventana != null) {
            ventana.salida().append(texto, tipo);
        } else if (texto != null && !texto.isEmpty()) {
            System.out.println(texto);
        }
    }

    private void mostrarMarcadorEnTexto(JSONArray entradas) {
        if (entradas == null || entradas.length() == 0) {
            return;
        }
        escribir("--- MARCADOR ---", OutputPanel.Tipo.TITULO);
        for (int i = 0; i < entradas.length(); i++) {
            JSONObject e = entradas.getJSONObject(i);
            escribir(String.format("%2d. %-16s %5d pts   %d/%d victorias",
                    e.optInt(Protocol.P_RANK, i + 1),
                    e.optString(Protocol.P_NAME, "?"),
                    e.optInt(Protocol.P_SCORE, 0),
                    e.optInt(Protocol.P_WINS, 0),
                    e.optInt(Protocol.P_GAMES, 0)), OutputPanel.Tipo.SISTEMA);
        }
    }

    // =======================================================================
    // main
    // =======================================================================

    public static void main(String[] args) {
        String host = Protocol.DEFAULT_HOST;
        int puerto = Protocol.DEFAULT_PORT;
        boolean consola = false;

        // Los argumentos posicionales son host y puerto; --nogui puede ir en
        // cualquier posicion. Sin argumentos se usan los valores por defecto.
        int posicional = 0;
        for (String arg : args) {
            String a = arg.trim();
            if (a.equalsIgnoreCase("--nogui") || a.equalsIgnoreCase("-nogui")) {
                consola = true;
            } else if (posicional == 0) {
                host = a;
                posicional++;
            } else if (posicional == 1) {
                try {
                    puerto = Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: el puerto debe ser un número entero. "
                            + "Se recibió: '" + a + "'");
                    System.err.println("Uso: gradle :tcp:runClient -Phost=localhost -Pport=8080");
                    System.exit(1);
                }
                posicional++;
            }
        }

        if (puerto < 1 || puerto > 65535) {
            System.err.println("ERROR: el puerto debe estar entre 1 y 65535.");
            System.exit(1);
        }

        // Sin entorno gráfico (servidor remoto por SSH) se cae al modo consola.
        if (!consola && GraphicsEnvironment.isHeadless()) {
            System.out.println("(No hay entorno gráfico disponible: se usa el modo consola)");
            consola = true;
        }

        if (args.length == 0) {
            System.out.println("(sin argumentos: se usa " + host + ":" + puerto + ")");
        }

        new GameClient(host, puerto, consola).iniciar();
    }
}
