/*
 * Archivo : PicturePanel.java
 * Paquete : client.gui
 *
 * Descripcion:
 *   Panel que muestra la imagen enviada por el servidor. Internamente trabaja
 *   con una rejilla de baldosas (por defecto 2x2) construida con GridMaker:
 *
 *     - al recibir una frase nueva solo se ve la primera baldosa
 *     - cada vez que el jugador pide otra pista se descubre una baldosa mas
 *     - al acertar, perder o ganar se muestra la imagen completa
 *
 *   Las baldosas ocultas se dibujan como un recuadro oscuro con un signo de
 *   interrogacion, de modo que siempre se vea algo coherente.
 *
 *   El dibujo se hace en paintComponent escalando al vuelo y conservando la
 *   proporcion, asi que el panel se adapta al redimensionar la ventana.
 */

package client.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/**
 * Panel de imagen con revelado por baldosas.
 */
public class PicturePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Color FONDO = new Color(18, 20, 28);
    private static final Color BALDOSA_OCULTA = new Color(38, 42, 56);
    private static final Color BORDE = new Color(72, 80, 102);
    private static final Color TEXTO_TENUE = new Color(150, 158, 180);

    private int filas = 2;
    private int columnas = 2;

    private BufferedImage imagenCompleta;
    private BufferedImage[][] baldosas;
    private boolean[][] visibles;
    private int[][] ordenRevelado;

    /** Si es true se ignora la rejilla y se pinta la imagen entera. */
    private boolean mostrarCompleta = true;

    private String mensajeVacio = "Esperando al servidor...";

    public PicturePanel() {
        setBackground(FONDO);
        setPreferredSize(new Dimension(520, 360));
        setMinimumSize(new Dimension(300, 220));
    }

    // =======================================================================
    // API publica
    // =======================================================================

    /** Define el tamano de la rejilla. Por defecto 2x2. */
    public void setGrid(int filas, int columnas) {
        this.filas = Math.max(1, filas);
        this.columnas = Math.max(1, columnas);
        reconstruirRejilla();
        repaint();
    }

    /**
     * Carga una imagen nueva para adivinar: se parte en baldosas y solo queda
     * visible la primera.
     *
     * @param imagen         imagen ya decodificada (puede ser null)
     * @param baldosasVisibles cuantas baldosas mostrar de entrada (minimo 1)
     */
    public void mostrarOculta(BufferedImage imagen, int baldosasVisibles) {
        this.imagenCompleta = imagen;
        this.mostrarCompleta = false;
        reconstruirRejilla();
        revelar(baldosasVisibles);
        repaint();
    }

    /** Muestra la imagen completa, sin baldosas ocultas. */
    public void mostrarCompleta(BufferedImage imagen) {
        this.imagenCompleta = imagen;
        this.mostrarCompleta = true;
        reconstruirRejilla();
        repaint();
    }

    /**
     * Descubre baldosas hasta llegar a la cantidad indicada.
     *
     * @param cantidad numero total de baldosas que deben quedar visibles
     */
    public void revelar(int cantidad) {
        if (visibles == null || ordenRevelado == null) {
            return;
        }
        int total = filas * columnas;
        int objetivo = Math.max(1, Math.min(cantidad, total));
        for (int i = 0; i < total; i++) {
            int f = ordenRevelado[i][0];
            int c = ordenRevelado[i][1];
            visibles[f][c] = i < objetivo;
        }
        repaint();
    }

    /** Descubre todas las baldosas. */
    public void revelarTodo() {
        this.mostrarCompleta = true;
        repaint();
    }

    /** Limpia el panel y muestra un texto de espera. */
    public void limpiar(String mensaje) {
        this.imagenCompleta = null;
        this.baldosas = null;
        this.visibles = null;
        this.mensajeVacio = mensaje;
        this.mostrarCompleta = true;
        repaint();
    }

    /** @return true si hay una imagen cargada. */
    public boolean tieneImagen() {
        return imagenCompleta != null;
    }

    // =======================================================================
    // Interno
    // =======================================================================

    private void reconstruirRejilla() {
        if (imagenCompleta == null) {
            baldosas = null;
            visibles = null;
            ordenRevelado = null;
            return;
        }
        baldosas = GridMaker.slice(imagenCompleta, filas, columnas);
        visibles = new boolean[filas][columnas];
        ordenRevelado = GridMaker.ordenDeRevelado(filas, columnas);
    }

    // =======================================================================
    // Dibujo
    // =======================================================================

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        GridMaker.aplicarCalidad(g);

        int w = getWidth();
        int h = getHeight();

        g.setColor(FONDO);
        g.fillRect(0, 0, w, h);

        if (imagenCompleta == null) {
            dibujarMensaje(g, w, h, mensajeVacio);
            g.dispose();
            return;
        }

        // Rectangulo destino conservando la proporcion de la imagen original.
        int margen = 10;
        double escala = Math.min(
                (w - 2.0 * margen) / imagenCompleta.getWidth(),
                (h - 2.0 * margen) / imagenCompleta.getHeight());
        escala = Math.max(0.01, escala);

        int dw = (int) Math.round(imagenCompleta.getWidth() * escala);
        int dh = (int) Math.round(imagenCompleta.getHeight() * escala);
        int dx = (w - dw) / 2;
        int dy = (h - dh) / 2;

        // Sombra suave detras de la imagen
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(dx + 4, dy + 5, dw, dh, 10, 10);

        if (mostrarCompleta || baldosas == null) {
            g.drawImage(imagenCompleta, dx, dy, dw, dh, null);
        } else {
            dibujarRejilla(g, dx, dy, dw, dh);
        }

        // Marco
        g.setColor(BORDE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(dx, dy, dw, dh, 8, 8);

        g.dispose();
    }

    private void dibujarRejilla(Graphics2D g, int dx, int dy, int dw, int dh) {
        double anchoBaldosa = dw / (double) columnas;
        double altoBaldosa = dh / (double) filas;

        for (int f = 0; f < filas; f++) {
            for (int c = 0; c < columnas; c++) {
                int x = dx + (int) Math.round(c * anchoBaldosa);
                int y = dy + (int) Math.round(f * altoBaldosa);
                int x2 = dx + (int) Math.round((c + 1) * anchoBaldosa);
                int y2 = dy + (int) Math.round((f + 1) * altoBaldosa);
                int bw = x2 - x;
                int bh = y2 - y;

                if (visibles != null && visibles[f][c] && baldosas[f][c] != null) {
                    g.drawImage(baldosas[f][c], x, y, bw, bh, null);
                } else {
                    g.setColor(BALDOSA_OCULTA);
                    g.fillRect(x, y, bw, bh);
                    g.setColor(TEXTO_TENUE);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD,
                            Math.max(16, Math.min(bw, bh) / 3)));
                    String q = "?";
                    Rectangle2D r = g.getFontMetrics().getStringBounds(q, g);
                    g.drawString(q,
                            x + (int) ((bw - r.getWidth()) / 2),
                            y + (int) ((bh + r.getHeight() * 0.65) / 2));
                }

                // Separacion entre baldosas
                g.setColor(new Color(12, 14, 20, 180));
                g.setStroke(new BasicStroke(2f));
                g.drawRect(x, y, bw, bh);
            }
        }
    }

    private void dibujarMensaje(Graphics2D g, int w, int h, String mensaje) {
        g.setColor(new Color(255, 255, 255, 26));
        g.drawRoundRect(14, 14, w - 29, h - 29, 12, 12);

        g.setColor(TEXTO_TENUE);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        String[] lineas = mensaje.split("\n");
        int altoLinea = g.getFontMetrics().getHeight();
        int y = h / 2 - (lineas.length - 1) * altoLinea / 2;
        for (String linea : lineas) {
            Rectangle2D r = g.getFontMetrics().getStringBounds(linea, g);
            g.drawString(linea, (int) ((w - r.getWidth()) / 2), y);
            y += altoLinea;
        }
    }
}
