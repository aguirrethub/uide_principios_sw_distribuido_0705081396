/*
 * Archivo : GridMaker.java
 * Paquete : client.gui
 *
 * Descripcion:
 *   Utilidades de imagen para la interfaz grafica:
 *     - decodificar un PNG que llego en Base64 dentro de un mensaje JSON
 *     - partir una imagen en una rejilla de baldosas (de ahi el nombre)
 *     - escalar conservando la proporcion
 *     - generar imagenes de relleno cuando el servidor no manda nada
 *
 *   La rejilla la usa PicturePanel para revelar la imagen por partes:
 *   al empezar cada frase solo se ve una baldosa y, con cada pista que pide
 *   el jugador, se descubre una mas.
 */

package client.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Utilidades de imagen y de rejilla. Clase de utilidad: no se instancia.
 */
public final class GridMaker {

    private GridMaker() {
    }

    // =======================================================================
    // Base64 -> imagen
    // =======================================================================

    /**
     * Decodifica una imagen PNG recibida en Base64.
     *
     * @param base64 contenido del campo "image" del payload
     * @return la imagen, o null si el texto esta vacio o no es una imagen valida
     */
    public static BufferedImage fromBase64(String base64) {
        if (base64 == null || base64.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] datos = Base64.getDecoder().decode(base64);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(datos));
            return img;
        } catch (IllegalArgumentException e) {
            System.err.println("[GridMaker] Base64 inválido: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("[GridMaker] No se pudo decodificar la imagen: "
                    + e.getMessage());
            return null;
        }
    }

    // =======================================================================
    // Rejilla
    // =======================================================================

    /**
     * Parte una imagen en una rejilla de filas x columnas.
     *
     * @param origen   imagen completa
     * @param filas    numero de filas (>= 1)
     * @param columnas numero de columnas (>= 1)
     * @return matriz [fila][columna] de baldosas, o null si la imagen es null
     */
    public static BufferedImage[][] slice(BufferedImage origen, int filas, int columnas) {
        if (origen == null) {
            return null;
        }
        filas = Math.max(1, filas);
        columnas = Math.max(1, columnas);

        int ancho = origen.getWidth() / columnas;
        int alto = origen.getHeight() / filas;
        BufferedImage[][] rejilla = new BufferedImage[filas][columnas];

        for (int f = 0; f < filas; f++) {
            for (int c = 0; c < columnas; c++) {
                int x = c * ancho;
                int y = f * alto;
                // La ultima fila/columna absorbe los pixeles sobrantes de la division.
                int w = (c == columnas - 1) ? origen.getWidth() - x : ancho;
                int h = (f == filas - 1) ? origen.getHeight() - y : alto;
                rejilla[f][c] = origen.getSubimage(x, y, Math.max(1, w), Math.max(1, h));
            }
        }
        return rejilla;
    }

    /**
     * Orden en que se descubren las baldosas de una rejilla 2x2:
     * superior izquierda, inferior derecha, superior derecha, inferior izquierda.
     * Se usa para que la revelacion progresiva se vea equilibrada.
     */
    public static int[][] ordenDeRevelado(int filas, int columnas) {
        int total = filas * columnas;
        int[][] orden = new int[total][2];
        int i = 0;
        for (int f = 0; f < filas; f++) {
            for (int c = 0; c < columnas; c++) {
                orden[i][0] = f;
                orden[i][1] = c;
                i++;
            }
        }
        // Intercala para que no se descubra siempre de izquierda a derecha.
        for (int a = 1; a + 1 < total; a += 2) {
            int[] tmp = orden[a];
            orden[a] = orden[total - a];
            orden[total - a] = tmp;
        }
        return orden;
    }

    // =======================================================================
    // Escalado
    // =======================================================================

    /**
     * Escala una imagen para que quepa dentro de un rectangulo sin deformarla.
     *
     * @return nueva imagen escalada, o null si la entrada era null
     */
    public static BufferedImage scaleToFit(BufferedImage origen, int maxAncho, int maxAlto) {
        if (origen == null || maxAncho <= 0 || maxAlto <= 0) {
            return null;
        }
        double escala = Math.min(
                maxAncho / (double) origen.getWidth(),
                maxAlto / (double) origen.getHeight());
        int w = Math.max(1, (int) Math.round(origen.getWidth() * escala));
        int h = Math.max(1, (int) Math.round(origen.getHeight() * escala));

        BufferedImage destino = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = destino.createGraphics();
        aplicarCalidad(g);
        g.drawImage(origen, 0, 0, w, h, null);
        g.dispose();
        return destino;
    }

    /** Activa interpolacion y antialiasing de buena calidad. */
    public static void aplicarCalidad(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    // =======================================================================
    // Imagen de relleno
    // =======================================================================

    /**
     * Genera una imagen de relleno con un texto centrado.
     * Se usa cuando todavia no hay partida o cuando el servidor no envio imagen.
     */
    public static BufferedImage placeholder(int ancho, int alto, String texto) {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        aplicarCalidad(g);

        g.setPaint(new GradientPaint(0, 0, new Color(28, 32, 44),
                                     ancho, alto, new Color(52, 60, 82)));
        g.fillRect(0, 0, ancho, alto);

        g.setColor(new Color(255, 255, 255, 40));
        g.drawRect(12, 12, ancho - 25, alto - 25);

        g.setColor(new Color(226, 232, 245));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        dibujarCentrado(g, texto, ancho, alto);

        g.dispose();
        return img;
    }

    /** Dibuja texto centrado, partiendolo en lineas si contiene '\n'. */
    private static void dibujarCentrado(Graphics2D g, String texto, int ancho, int alto) {
        if (texto == null) {
            return;
        }
        String[] lineas = texto.split("\n");
        FontRenderContext frc = g.getFontRenderContext();
        int altoLinea = g.getFontMetrics().getHeight();
        int y = alto / 2 - (lineas.length - 1) * altoLinea / 2;

        for (String linea : lineas) {
            Rectangle2D r = g.getFont().getStringBounds(linea, frc);
            g.drawString(linea, (int) ((ancho - r.getWidth()) / 2), y);
            y += altoLinea;
        }
    }
}
