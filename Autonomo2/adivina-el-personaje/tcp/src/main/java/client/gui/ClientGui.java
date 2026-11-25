/*
 * Archivo : ClientGui.java
 * Paquete : client.gui
 *
 * Descripcion:
 *   Ventana principal del juego. Se compone de:
 *
 *     +-------------------------------------------------------------+
 *     |  cabecera: jugador | tiempo | aciertos | puntos              |
 *     +----------------------------+--------------------------------+
 *     |                            |                                |
 *     |      PicturePanel          |        OutputPanel             |
 *     |  (imagen por baldosas)     |   (dialogo con el servidor)    |
 *     |                            |                                |
 *     +----------------------------+--------------------------------+
 *     |  frase del personaje                                        |
 *     |  [ respuesta............ ]  ADIVINAR  PISTA  SIGUIENTE ...  |
 *     +-------------------------------------------------------------+
 *
 *   La ventana NO conoce la red: cuando el usuario pulsa un boton llama a la
 *   interfaz Acciones, que implementa GameClient. Y cuando llega algo del
 *   servidor, GameClient invoca los metodos mostrarXxx de esta clase. Esa
 *   separacion mantiene la logica de red fuera de la interfaz.
 *
 *   Todo lo que toca componentes Swing se ejecuta en el Event Dispatch Thread.
 */

package client.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * Ventana principal del cliente.
 */
public class ClientGui extends JFrame {

    private static final long serialVersionUID = 1L;

    // --- Paleta -----------------------------------------------------------
    private static final Color FONDO = new Color(16, 18, 26);
    private static final Color PANEL = new Color(26, 29, 40);
    private static final Color BORDE = new Color(48, 54, 72);
    private static final Color TEXTO = new Color(226, 232, 245);
    private static final Color TENUE = new Color(140, 150, 174);
    private static final Color ACENTO = new Color(246, 206, 112);
    private static final Color VERDE = new Color(96, 198, 122);
    private static final Color ROJO = new Color(230, 96, 92);
    private static final Color AZUL = new Color(86, 150, 232);

    /** Acciones que la ventana pide al cliente. Las implementa GameClient. */
    public interface Acciones {
        void iniciarPartida();

        void adivinar(String respuesta);

        void pedirPista();

        void siguientePersonaje();

        void pedirMarcador();

        void salir();
    }

    private final Acciones acciones;

    // --- Componentes ------------------------------------------------------
    private final PicturePanel panelImagen = new PicturePanel();
    private final OutputPanel panelSalida = new OutputPanel();

    private final JLabel lblJugador = new JLabel("-");
    private final JLabel lblAciertos = new JLabel("0 / 3");
    private final JLabel lblPuntos = new JLabel("0");
    private final JLabel lblTiempo = new JLabel("60 s");
    private final JProgressBar barraTiempo = new JProgressBar(0, 60);
    private final JLabel lblConexion = new JLabel("desconectado");

    private final JTextArea areaFrase = new JTextArea(2, 40);
    private final JTextField campoRespuesta = new JTextField();

    private final JButton btnIniciar = boton("INICIAR", VERDE);
    private final JButton btnAdivinar = boton("ADIVINAR", AZUL);
    private final JButton btnPista = boton("OTRA PISTA", new Color(108, 116, 142));
    private final JButton btnSiguiente = boton("SIGUIENTE", new Color(108, 116, 142));
    private final JButton btnMarcador = boton("MARCADOR", new Color(108, 116, 142));
    private final JButton btnSalir = boton("SALIR", ROJO);

    // --- Cuenta atras local ----------------------------------------------
    private final Timer relojLocal;
    private int segundosRestantes = 0;
    private boolean jugando = false;

    public ClientGui(String titulo, Acciones acciones) {
        super(titulo);
        this.acciones = acciones;

        construir();

        // Reloj local: el servidor manda el tiempo real en cada mensaje; este
        // temporizador solo hace que la cuenta atras se vea fluida entre uno
        // y otro. Nunca decide el fin de la partida: eso lo hace el servidor.
        relojLocal = new Timer(1000, (ActionEvent e) -> {
            if (jugando && segundosRestantes > 0) {
                segundosRestantes--;
                pintarTiempo();
            }
        });

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                acciones.salir();
            }
        });

        setMinimumSize(new Dimension(900, 620));
        setSize(1060, 700);
        setLocationRelativeTo(null);
        estadoDesconectado();
    }

    // =======================================================================
    // Construccion de la interfaz
    // =======================================================================

    private void construir() {
        JPanel raiz = new JPanel(new BorderLayout(0, 0));
        raiz.setBackground(FONDO);
        raiz.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        raiz.add(crearCabecera(), BorderLayout.NORTH);
        raiz.add(crearCentro(), BorderLayout.CENTER);
        raiz.add(crearPie(), BorderLayout.SOUTH);

        setContentPane(raiz);
    }

    private JPanel crearCabecera() {
        JPanel cab = new JPanel(new BorderLayout(10, 6));
        cab.setBackground(FONDO);
        cab.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        // Fila 1: titulo + estado de conexion
        JPanel fila1 = new JPanel(new BorderLayout());
        fila1.setBackground(FONDO);

        JLabel titulo = new JLabel("ADIVINA EL PERSONAJE");
        titulo.setForeground(ACENTO);
        titulo.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        fila1.add(titulo, BorderLayout.WEST);

        lblConexion.setForeground(TENUE);
        lblConexion.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        lblConexion.setHorizontalAlignment(SwingConstants.RIGHT);
        fila1.add(lblConexion, BorderLayout.EAST);

        // Fila 2: indicadores
        JPanel fila2 = new JPanel(new GridLayout(1, 4, 10, 0));
        fila2.setBackground(FONDO);
        fila2.add(indicador("JUGADOR", lblJugador, TEXTO));
        fila2.add(indicador("ACIERTOS", lblAciertos, VERDE));
        fila2.add(indicador("PUNTOS", lblPuntos, ACENTO));
        fila2.add(crearIndicadorTiempo());

        cab.add(fila1, BorderLayout.NORTH);
        cab.add(fila2, BorderLayout.CENTER);
        return cab;
    }

    private JPanel indicador(String etiqueta, JLabel valor, Color colorValor) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDE),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JLabel lbl = new JLabel(etiqueta);
        lbl.setForeground(TENUE);
        lbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        valor.setForeground(colorValor);
        valor.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        valor.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(lbl);
        p.add(valor);
        return p;
    }

    private JPanel crearIndicadorTiempo() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDE),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JLabel lbl = new JLabel("TIEMPO");
        lbl.setForeground(TENUE);
        lbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        lblTiempo.setForeground(VERDE);
        lblTiempo.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        lblTiempo.setAlignmentX(Component.LEFT_ALIGNMENT);

        barraTiempo.setValue(60);
        barraTiempo.setForeground(VERDE);
        barraTiempo.setBackground(new Color(44, 48, 62));
        barraTiempo.setBorderPainted(false);
        barraTiempo.setPreferredSize(new Dimension(10, 6));
        barraTiempo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        barraTiempo.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(lbl);
        p.add(lblTiempo);
        p.add(Box.createVerticalStrut(3));
        p.add(barraTiempo);
        return p;
    }

    private JSplitPane crearCentro() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                panelImagen, panelSalida);
        split.setResizeWeight(0.58);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(FONDO);
        split.setDividerSize(8);
        return split;
    }

    private JPanel crearPie() {
        JPanel pie = new JPanel(new BorderLayout(0, 8));
        pie.setBackground(FONDO);
        pie.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        // Frase del personaje
        areaFrase.setEditable(false);
        areaFrase.setLineWrap(true);
        areaFrase.setWrapStyleWord(true);
        areaFrase.setBackground(PANEL);
        areaFrase.setForeground(ACENTO);
        areaFrase.setFont(new Font(Font.SERIF, Font.ITALIC, 19));
        areaFrase.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDE),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        areaFrase.setText("Pulsa INICIAR para empezar la partida.");

        // Fila de controles
        JPanel controles = new JPanel(new BorderLayout(8, 0));
        controles.setBackground(FONDO);

        campoRespuesta.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        campoRespuesta.setBackground(new Color(34, 38, 52));
        campoRespuesta.setForeground(TEXTO);
        campoRespuesta.setCaretColor(ACENTO);
        campoRespuesta.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDE),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        campoRespuesta.setToolTipText("Escribe el nombre del personaje y pulsa Enter");
        campoRespuesta.addActionListener(e -> enviarRespuesta());

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.setBackground(FONDO);
        botones.add(btnIniciar);
        botones.add(btnAdivinar);
        botones.add(btnPista);
        botones.add(btnSiguiente);
        botones.add(btnMarcador);
        botones.add(btnSalir);

        btnIniciar.addActionListener(e -> acciones.iniciarPartida());
        btnAdivinar.addActionListener(e -> enviarRespuesta());
        btnPista.addActionListener(e -> acciones.pedirPista());
        btnSiguiente.addActionListener(e -> acciones.siguientePersonaje());
        btnMarcador.addActionListener(e -> acciones.pedirMarcador());
        btnSalir.addActionListener(e -> acciones.salir());

        btnIniciar.setMnemonic(KeyEvent.VK_I);
        btnAdivinar.setMnemonic(KeyEvent.VK_A);
        btnPista.setMnemonic(KeyEvent.VK_P);
        btnSiguiente.setMnemonic(KeyEvent.VK_S);

        controles.add(campoRespuesta, BorderLayout.CENTER);
        controles.add(botones, BorderLayout.EAST);

        pie.add(areaFrase, BorderLayout.NORTH);
        pie.add(controles, BorderLayout.CENTER);
        return pie;
    }

    private JButton boton(String texto, Color color) {
        JButton b = new JButton(texto);
        b.setFocusPainted(false);
        b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        b.setBackground(color);
        b.setForeground(new Color(14, 16, 22));
        b.setOpaque(true);
        b.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        return b;
    }

    private void enviarRespuesta() {
        String texto = campoRespuesta.getText().trim();
        if (texto.isEmpty()) {
            panelSalida.fallo("Escribe un nombre antes de pulsar ADIVINAR.");
            return;
        }
        campoRespuesta.setText("");
        acciones.adivinar(texto);
    }

    // =======================================================================
    // API que usa GameClient (segura desde cualquier hilo)
    // =======================================================================

    /** @return el panel de salida, para escribir mensajes. */
    public OutputPanel salida() {
        return panelSalida;
    }

    /** Actualiza la etiqueta de conexion de la cabecera. */
    public void setConexion(final String texto, final boolean conectado) {
        enEdt(() -> {
            lblConexion.setText(texto);
            lblConexion.setForeground(conectado ? VERDE : ROJO);
        });
    }

    /** Pone el nombre del jugador en la cabecera. */
    public void setJugador(final String nombre) {
        enEdt(() -> lblJugador.setText(nombre));
    }

    /**
     * Refresca los indicadores con el estado que informa el servidor.
     *
     * @param aciertos frases acertadas
     * @param objetivo frases necesarias
     * @param puntos   puntaje acumulado
     * @param segundos segundos restantes segun el servidor
     */
    public void setEstado(final int aciertos, final int objetivo,
                          final int puntos, final int segundos) {
        enEdt(() -> {
            lblAciertos.setText(aciertos + " / " + objetivo);
            lblPuntos.setText(String.valueOf(puntos));
            segundosRestantes = Math.max(0, segundos);
            barraTiempo.setMaximum(Math.max(1, Math.max(segundosRestantes, barraTiempo.getMaximum())));
            pintarTiempo();
        });
    }

    /** Muestra la frase del personaje en el pie de la ventana. */
    public void setFrase(final String frase) {
        enEdt(() -> areaFrase.setText(frase));
    }

    /**
     * Muestra una imagen nueva oculta por baldosas.
     *
     * @param imagen            imagen decodificada
     * @param baldosasVisibles  cuantas baldosas descubrir de entrada
     */
    public void mostrarImagenOculta(final BufferedImage imagen, final int baldosasVisibles) {
        enEdt(() -> {
            if (imagen != null) {
                panelImagen.mostrarOculta(imagen, baldosasVisibles);
            }
        });
    }

    /** Descubre baldosas hasta la cantidad indicada. */
    public void revelarBaldosas(final int cantidad) {
        enEdt(() -> panelImagen.revelar(cantidad));
    }

    /** Muestra una imagen completa (victoria, derrota, revelacion). */
    public void mostrarImagenCompleta(final BufferedImage imagen) {
        enEdt(() -> {
            if (imagen != null) {
                panelImagen.mostrarCompleta(imagen);
            } else {
                panelImagen.revelarTodo();
            }
        });
    }

    /** Vacía el panel de imagen con un texto de espera. */
    public void limpiarImagen(final String mensaje) {
        enEdt(() -> panelImagen.limpiar(mensaje));
    }

    // =======================================================================
    // Estados de la interfaz
    // =======================================================================

    /** Antes de conectar: todo deshabilitado. */
    public final void estadoDesconectado() {
        enEdt(() -> {
            jugando = false;
            if (relojLocal != null) {
                relojLocal.stop();
            }
            btnIniciar.setEnabled(false);
            btnAdivinar.setEnabled(false);
            btnPista.setEnabled(false);
            btnSiguiente.setEnabled(false);
            btnMarcador.setEnabled(false);
            btnSalir.setEnabled(true);
            campoRespuesta.setEnabled(false);
        });
    }

    /** Conectado pero sin partida en curso. */
    public void estadoLobby() {
        enEdt(() -> {
            jugando = false;
            relojLocal.stop();
            btnIniciar.setEnabled(true);
            btnAdivinar.setEnabled(false);
            btnPista.setEnabled(false);
            btnSiguiente.setEnabled(false);
            btnMarcador.setEnabled(true);
            btnSalir.setEnabled(true);
            campoRespuesta.setEnabled(false);
            campoRespuesta.setText("");
            lblTiempo.setForeground(TENUE);
            lblTiempo.setText("--");
            barraTiempo.setValue(barraTiempo.getMaximum());
            barraTiempo.setForeground(TENUE);
        });
    }

    /** Partida en curso. */
    public void estadoJugando() {
        enEdt(() -> {
            jugando = true;
            btnIniciar.setEnabled(false);
            btnAdivinar.setEnabled(true);
            btnPista.setEnabled(true);
            btnSiguiente.setEnabled(true);
            btnMarcador.setEnabled(true);
            btnSalir.setEnabled(true);
            campoRespuesta.setEnabled(true);
            campoRespuesta.requestFocusInWindow();
            relojLocal.start();
        });
    }

    /** Partida terminada, esperando respuesta de "¿jugar otra vez?". */
    public void estadoFinPartida() {
        enEdt(() -> {
            jugando = false;
            relojLocal.stop();
            btnAdivinar.setEnabled(false);
            btnPista.setEnabled(false);
            btnSiguiente.setEnabled(false);
            campoRespuesta.setEnabled(false);
            lblTiempo.setText("0 s");
            barraTiempo.setValue(0);
        });
    }

    private void pintarTiempo() {
        lblTiempo.setText(segundosRestantes + " s");
        barraTiempo.setValue(segundosRestantes);
        Color c = segundosRestantes <= 10 ? ROJO
                : segundosRestantes <= 25 ? ACENTO : VERDE;
        lblTiempo.setForeground(c);
        barraTiempo.setForeground(c);
    }

    // =======================================================================
    // Diálogos
    // =======================================================================

    /** Pide el nombre del jugador al arrancar. */
    public String pedirNombre(String porDefecto) {
        String nombre = (String) JOptionPane.showInputDialog(this,
                "¿Cómo te llamas, jugador?",
                "Adivina el Personaje",
                JOptionPane.QUESTION_MESSAGE, null, null, porDefecto);
        if (nombre == null || nombre.trim().isEmpty()) {
            return porDefecto;
        }
        return nombre.trim();
    }

    /**
     * Pregunta si desea seguir jugando.
     *
     * @return true si quiere otra partida
     */
    public boolean preguntarContinuar(String mensaje) {
        // Se usa showOptionDialog con textos propios en lugar de
        // showConfirmDialog, porque este último rotula los botones según el
        // idioma del sistema y en un equipo en inglés saldrían "Yes"/"No".
        Object[] opciones = {"Sí, otra partida", "No, salir"};
        int r = JOptionPane.showOptionDialog(this,
                mensaje + "\n\n¿Deseas jugar otra partida?",
                "Fin de la partida",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, opciones, opciones[0]);
        return r == JOptionPane.YES_OPTION;
    }

    /** Muestra un aviso simple. */
    public void aviso(String titulo, String mensaje) {
        enEdt(() -> JOptionPane.showMessageDialog(this, mensaje, titulo,
                JOptionPane.WARNING_MESSAGE));
    }

    /**
     * Muestra el marcador en una ventana con tabla.
     *
     * @param filas matriz [n][5] con puesto, jugador, puntos, victorias, fecha
     */
    public void mostrarMarcador(final Object[][] filas) {
        enEdt(() -> {
            String[] columnas = {"#", "Jugador", "Puntos", "Victorias", "Última partida"};
            DefaultTableModel modelo = new DefaultTableModel(filas, columnas) {
                private static final long serialVersionUID = 1L;

                @Override
                public boolean isCellEditable(int fila, int columna) {
                    return false;
                }
            };

            JTable tabla = new JTable(modelo);
            tabla.setRowHeight(26);
            tabla.setBackground(PANEL);
            tabla.setForeground(TEXTO);
            tabla.setGridColor(BORDE);
            tabla.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            tabla.setSelectionBackground(new Color(60, 68, 92));

            JTableHeader cabecera = tabla.getTableHeader();
            cabecera.setBackground(new Color(38, 42, 58));
            cabecera.setForeground(ACENTO);
            cabecera.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

            tabla.getColumnModel().getColumn(0).setMaxWidth(44);
            tabla.getColumnModel().getColumn(2).setMaxWidth(80);
            tabla.getColumnModel().getColumn(3).setMaxWidth(90);

            JScrollPane scroll = new JScrollPane(tabla);
            scroll.setPreferredSize(new Dimension(560, 260));
            scroll.getViewport().setBackground(PANEL);

            JDialog dlg = new JDialog(this, "Marcador", true);
            JPanel cont = new JPanel(new BorderLayout(0, 8));
            cont.setBackground(FONDO);
            cont.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JLabel titulo = new JLabel("MEJORES JUGADORES");
            titulo.setForeground(ACENTO);
            titulo.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));

            JButton cerrar = boton("CERRAR", new Color(108, 116, 142));
            cerrar.addActionListener(e -> dlg.dispose());
            JPanel sur = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            sur.setBackground(FONDO);
            sur.add(cerrar);

            cont.add(titulo, BorderLayout.NORTH);
            cont.add(scroll, BorderLayout.CENTER);
            cont.add(sur, BorderLayout.SOUTH);

            dlg.setContentPane(cont);
            dlg.pack();
            dlg.setLocationRelativeTo(this);
            dlg.setVisible(true);
        });
    }

    // =======================================================================
    // Auxiliar
    // =======================================================================

    /** Ejecuta la tarea en el Event Dispatch Thread de Swing. */
    private void enEdt(Runnable tarea) {
        if (SwingUtilities.isEventDispatchThread()) {
            tarea.run();
        } else {
            SwingUtilities.invokeLater(tarea);
        }
    }
}
