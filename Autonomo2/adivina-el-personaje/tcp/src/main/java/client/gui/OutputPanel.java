/*
 * Archivo : OutputPanel.java
 * Paquete : client.gui
 *
 * Descripcion:
 *   Consola del juego dentro de la ventana: muestra el dialogo con el
 *   servidor con colores segun el tipo de linea (informacion, acierto, fallo,
 *   error, pista, sistema) y hace autoscroll.
 *
 *   Todos los metodos publicos se pueden llamar desde cualquier hilo: se
 *   reenvian al Event Dispatch Thread de Swing con invokeLater, porque los
 *   mensajes llegan desde el hilo lector del socket y Swing no es thread-safe.
 */

package client.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;

/**
 * Panel de salida de texto con estilos.
 */
public class OutputPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Color FONDO = new Color(22, 24, 32);
    private static final Color C_INFO = new Color(208, 214, 230);
    private static final Color C_SISTEMA = new Color(132, 142, 166);
    private static final Color C_ACIERTO = new Color(118, 214, 134);
    private static final Color C_FALLO = new Color(244, 170, 92);
    private static final Color C_ERROR = new Color(242, 108, 104);
    private static final Color C_PISTA = new Color(126, 190, 248);
    private static final Color C_TITULO = new Color(246, 206, 112);
    private static final Color C_HORA = new Color(96, 104, 124);

    /** Tipos de linea, cada uno con su color. */
    public enum Tipo { INFO, SISTEMA, ACIERTO, FALLO, ERROR, PISTA, TITULO }

    private final JTextPane area;
    private final StyledDocument doc;

    public OutputPanel() {
        setLayout(new BorderLayout());
        setBackground(FONDO);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        area = new JTextPane();
        area.setEditable(false);
        area.setBackground(FONDO);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setMargin(new java.awt.Insets(8, 10, 8, 10));
        doc = area.getStyledDocument();

        JScrollPane scroll = new JScrollPane(area);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(48, 54, 72)));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(400, 360));
        setMinimumSize(new Dimension(260, 160));
    }

    // =======================================================================
    // API publica (segura desde cualquier hilo)
    // =======================================================================

    /** Escribe una linea de tipo INFO. */
    public void info(String texto) {
        append(texto, Tipo.INFO);
    }

    /** Escribe una linea del sistema (conexiones, estado interno). */
    public void sistema(String texto) {
        append(texto, Tipo.SISTEMA);
    }

    /** Escribe una linea de acierto. */
    public void acierto(String texto) {
        append(texto, Tipo.ACIERTO);
    }

    /** Escribe una linea de fallo. */
    public void fallo(String texto) {
        append(texto, Tipo.FALLO);
    }

    /** Escribe una linea de error del protocolo o de red. */
    public void error(String texto) {
        append(texto, Tipo.ERROR);
    }

    /** Escribe una pista o frase del personaje. */
    public void pista(String texto) {
        append(texto, Tipo.PISTA);
    }

    /** Escribe un titulo o separador. */
    public void titulo(String texto) {
        append("", Tipo.SISTEMA);
        append(texto, Tipo.TITULO);
    }

    /**
     * Escribe una linea con el tipo indicado.
     * Es seguro llamarlo desde el hilo lector del socket.
     */
    public void append(final String texto, final Tipo tipo) {
        if (SwingUtilities.isEventDispatchThread()) {
            escribir(texto, tipo);
        } else {
            SwingUtilities.invokeLater(() -> escribir(texto, tipo));
        }
    }

    /** Borra todo el contenido. */
    public void limpiar() {
        SwingUtilities.invokeLater(() -> area.setText(""));
    }

    // =======================================================================
    // Interno (siempre en el EDT)
    // =======================================================================

    private void escribir(String texto, Tipo tipo) {
        try {
            if (texto == null || texto.isEmpty()) {
                doc.insertString(doc.getLength(), "\n", estilo(C_INFO, false));
            } else {
                doc.insertString(doc.getLength(),
                        "[" + LocalTime.now().format(HORA) + "] ",
                        estilo(C_HORA, false));
                doc.insertString(doc.getLength(), texto + "\n",
                        estilo(color(tipo), tipo == Tipo.TITULO || tipo == Tipo.ACIERTO));
            }
            area.setCaretPosition(doc.getLength()); // autoscroll
        } catch (BadLocationException e) {
            // Imposible en la practica: siempre se inserta al final del documento.
            System.err.println("[OutputPanel] " + e.getMessage());
        }
    }

    private SimpleAttributeSet estilo(Color color, boolean negrita) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, negrita);
        return a;
    }

    private Color color(Tipo tipo) {
        switch (tipo) {
            case ACIERTO: return C_ACIERTO;
            case FALLO:   return C_FALLO;
            case ERROR:   return C_ERROR;
            case PISTA:   return C_PISTA;
            case TITULO:  return C_TITULO;
            case SISTEMA: return C_SISTEMA;
            case INFO:
            default:      return C_INFO;
        }
    }
}
