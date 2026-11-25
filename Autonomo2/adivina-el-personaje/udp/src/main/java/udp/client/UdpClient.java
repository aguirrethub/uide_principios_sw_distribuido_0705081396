/*
 * Archivo : UdpClient.java
 * Paquete : udp.client
 *
 * Descripcion:
 *   Cliente UDP de la version reducida. Envia su nombre y recibe una imagen.
 *
 *   Como UDP no garantiza entrega ni orden, el cliente hace tres cosas que un
 *   cliente TCP no necesitaria:
 *
 *     1. Reintenta el NAME si no llega el IMAGE_INFO (el datagrama inicial se
 *        pudo perder y aqui no hay retransmision automatica).
 *     2. Guarda los trozos en un mapa indexado por numero de secuencia, porque
 *        pueden llegar desordenados.
 *     3. Detecta los huecos y pide los trozos que faltan con RESEND, varias
 *        veces si hace falta.
 *
 *   Al terminar guarda el PNG en disco y, si hay entorno grafico, lo muestra
 *   en una ventana.
 *
 * Uso:
 *   gradle :udp:runClient
 *   gradle :udp:runClient -Phost=localhost -Pport=8080 -Pname=Ana
 *   java udp.client.UdpClient [host] [puerto] [nombre]
 */

package udp.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONException;
import udp.common.UdpMessage;
import udp.common.UdpProtocol;

/**
 * Cliente UDP: envia un nombre y reensambla la imagen que devuelve el servidor.
 */
public class UdpClient {

    private final String host;
    private final int puerto;
    private final String nombre;

    private DatagramSocket socket;
    private InetAddress direccionServidor;

    public UdpClient(String host, int puerto, String nombre) {
        this.host = host;
        this.puerto = puerto;
        this.nombre = nombre;
    }

    // =======================================================================
    // Flujo principal
    // =======================================================================

    /**
     * Ejecuta el intercambio completo.
     *
     * @return true si la imagen se recibio y guardo correctamente
     */
    public boolean ejecutar() {
        cabecera();

        try {
            direccionServidor = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            System.err.println("ERROR: no se pudo resolver el host '" + host + "'.");
            return false;
        }

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(UdpProtocol.TIMEOUT_MS);
            socket.setReceiveBufferSize(UdpProtocol.SOCKET_BUFFER);
        } catch (IOException e) {
            System.err.println("ERROR: no se pudo abrir el socket UDP: " + e.getMessage());
            return false;
        }

        try {
            return intercambiar();
        } finally {
            socket.close();
        }
    }

    private boolean intercambiar() {
        // --- Paso 1: enviar el nombre y esperar el IMAGE_INFO ---------------
        UdpMessage info = enviarNombreConReintentos();
        if (info == null) {
            System.err.println();
            System.err.println("ERROR: el servidor no respondió tras "
                    + UdpProtocol.MAX_REINTENTOS + " intentos.");
            System.err.println("       Comprueba que esté en marcha:");
            System.err.println("       gradle :udp:runServer -Pport=" + puerto);
            return false;
        }

        if (UdpProtocol.RES_ERROR.equals(info.getType())) {
            System.err.println("El servidor devolvió un error ["
                    + info.optString(UdpProtocol.P_CODE, "?") + "]: "
                    + info.optString(UdpProtocol.P_MESSAGE, ""));
            return false;
        }

        String transferId = info.optString(UdpProtocol.P_TRANSFER_ID, "");
        int totalChunks = info.optInt(UdpProtocol.P_CHUNKS, 0);
        String nombreImagen = info.optString(UdpProtocol.P_IMAGE_NAME, "imagen.png");

        System.out.println();
        System.out.println("Respuesta del servidor:");
        System.out.println("   " + info.optString(UdpProtocol.P_MESSAGE, ""));
        System.out.println("   transferId : " + transferId);
        System.out.println("   trozos     : " + totalChunks);
        System.out.println("   imagen     : " + nombreImagen);
        System.out.println();

        if (totalChunks <= 0) {
            System.err.println("ERROR: el servidor anunció 0 trozos.");
            return false;
        }

        // --- Paso 2: recibir los trozos ------------------------------------
        Map<Integer, String> recibidos = new HashMap<>();
        recibirChunks(recibidos, totalChunks, transferId);

        // --- Paso 3: pedir los que falten ----------------------------------
        int ronda = 0;
        while (recibidos.size() < totalChunks && ronda < UdpProtocol.MAX_REINTENTOS) {
            ronda++;
            List<Integer> faltantes = calcularFaltantes(recibidos, totalChunks);
            System.out.println("   Faltan " + faltantes.size() + " trozo(s). "
                    + "Pidiendo retransmisión (intento " + ronda + "/"
                    + UdpProtocol.MAX_REINTENTOS + ")...");

            JSONArray array = new JSONArray();
            for (Integer seq : faltantes) {
                array.put(seq.intValue());
            }
            enviar(UdpMessage.of(UdpProtocol.REQ_RESEND)
                    .put(UdpProtocol.P_TRANSFER_ID, transferId)
                    .put(UdpProtocol.P_MISSING, array));

            recibirChunks(recibidos, totalChunks, transferId);
        }

        if (recibidos.size() < totalChunks) {
            System.err.println();
            System.err.println("ERROR: solo se recibieron " + recibidos.size() + " de "
                    + totalChunks + " trozos. La imagen está incompleta.");
            return false;
        }

        // --- Paso 4: reensamblar y guardar ---------------------------------
        System.out.println("   Recibidos los " + totalChunks + " trozos. Reensamblando...");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < totalChunks; i++) {
            sb.append(recibidos.get(i));
        }

        byte[] imagen;
        try {
            imagen = Base64.getDecoder().decode(sb.toString());
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: los datos reensamblados no son Base64 válido.");
            return false;
        }

        Path destino = guardar(imagen, nombreImagen);
        if (destino == null) {
            return false;
        }

        System.out.println();
        System.out.println("=================================================================");
        System.out.println("   IMAGEN RECIBIDA CORRECTAMENTE");
        System.out.println("   " + imagen.length + " bytes  ->  " + destino.toAbsolutePath());
        System.out.println("=================================================================");

        mostrarVentana(imagen, destino);
        return true;
    }

    // =======================================================================
    // Pasos auxiliares
    // =======================================================================

    /** Envia NAME y espera IMAGE_INFO, reintentando si el datagrama se pierde. */
    private UdpMessage enviarNombreConReintentos() {
        for (int intento = 1; intento <= UdpProtocol.MAX_REINTENTOS; intento++) {
            System.out.println("Enviando nombre '" + nombre + "' a " + host + ":" + puerto
                    + "/udp  (intento " + intento + "/" + UdpProtocol.MAX_REINTENTOS + ")");

            enviar(UdpMessage.of(UdpProtocol.REQ_NAME).put(UdpProtocol.P_NAME, nombre));

            UdpMessage respuesta = recibirUno();
            if (respuesta != null
                    && (UdpProtocol.RES_IMAGE_INFO.equals(respuesta.getType())
                        || UdpProtocol.RES_ERROR.equals(respuesta.getType()))) {
                return respuesta;
            }
            System.out.println("   Sin respuesta; reintentando...");
        }
        return null;
    }

    /**
     * Recibe trozos hasta completarlos o hasta que se agote el tiempo de espera.
     * Los trozos pueden llegar desordenados: por eso se indexan por su numero.
     */
    private void recibirChunks(Map<Integer, String> recibidos, int totalChunks,
                               String transferId) {
        while (recibidos.size() < totalChunks) {
            UdpMessage m = recibirUno();
            if (m == null) {
                return; // timeout: se sale para pedir los que falten
            }
            if (UdpProtocol.RES_ERROR.equals(m.getType())) {
                System.err.println("   El servidor devolvió un error: "
                        + m.optString(UdpProtocol.P_MESSAGE, ""));
                return;
            }
            if (!UdpProtocol.RES_IMAGE_CHUNK.equals(m.getType())) {
                continue; // datagrama de otro tipo: se ignora
            }
            if (!transferId.equals(m.optString(UdpProtocol.P_TRANSFER_ID, ""))) {
                continue; // trozo de una transferencia antigua
            }

            int seq = m.optInt(UdpProtocol.P_SEQ, -1);
            String datos = m.optString(UdpProtocol.P_DATA, "");
            if (seq >= 0 && seq < totalChunks && !datos.isEmpty()) {
                if (recibidos.put(seq, datos) == null) {
                    progreso(recibidos.size(), totalChunks);
                }
            }
        }
        System.out.println();
    }

    private List<Integer> calcularFaltantes(Map<Integer, String> recibidos, int total) {
        List<Integer> faltantes = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (!recibidos.containsKey(i)) {
                faltantes.add(i);
            }
        }
        return faltantes;
    }

    private void progreso(int recibidos, int total) {
        int ancho = 30;
        int lleno = (int) Math.round(ancho * recibidos / (double) total);
        StringBuilder barra = new StringBuilder("\r   [");
        for (int i = 0; i < ancho; i++) {
            barra.append(i < lleno ? '=' : ' ');
        }
        barra.append("] ").append(recibidos).append('/').append(total);
        System.out.print(barra);
        System.out.flush();
    }

    private void enviar(UdpMessage mensaje) {
        try {
            socket.send(mensaje.toPacket(direccionServidor, puerto));
        } catch (IOException e) {
            System.err.println("   No se pudo enviar " + mensaje.getType() + ": "
                    + e.getMessage());
        }
    }

    /** Recibe un datagrama, o null si vence el tiempo de espera. */
    private UdpMessage recibirUno() {
        byte[] buffer = new byte[UdpProtocol.BUFFER_SIZE];
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(paquete);
            return UdpMessage.parse(paquete);
        } catch (SocketTimeoutException e) {
            return null;
        } catch (JSONException e) {
            System.err.println("   Datagrama ilegible: " + e.getMessage());
            return null;
        } catch (IOException e) {
            System.err.println("   Error de red: " + e.getMessage());
            return null;
        }
    }

    // =======================================================================
    // Salida
    // =======================================================================

    private Path guardar(byte[] imagen, String nombreImagen) {
        try {
            Path dir = Paths.get("udp-recibidas");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            String limpio = nombre.replaceAll("[^A-Za-z0-9_-]", "_");
            Path destino = dir.resolve(limpio + "_" + nombreImagen);
            Files.write(destino, imagen);
            return destino;
        } catch (IOException e) {
            System.err.println("ERROR: no se pudo guardar la imagen: " + e.getMessage());
            return null;
        }
    }

    /** Muestra la imagen en una ventana, si hay entorno grafico. */
    private void mostrarVentana(byte[] imagen, Path destino) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println();
            System.out.println("(Sin entorno gráfico: abre el archivo para ver la imagen)");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                Image img = ImageIO.read(new ByteArrayInputStream(imagen));
                if (img == null) {
                    System.err.println("La imagen recibida no se pudo decodificar.");
                    return;
                }

                JFrame frame = new JFrame("Imagen recibida por UDP  -  " + nombre);
                JPanel panel = new JPanel(new BorderLayout(0, 8));
                panel.setBackground(new Color(18, 20, 28));
                panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

                JLabel titulo = new JLabel("Hola " + nombre
                        + ", esta es tu imagen (recibida por UDP)");
                titulo.setForeground(new Color(246, 206, 112));
                titulo.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
                titulo.setHorizontalAlignment(SwingConstants.CENTER);

                JLabel lblImagen = new JLabel(new ImageIcon(img));
                lblImagen.setHorizontalAlignment(SwingConstants.CENTER);

                JLabel pie = new JLabel("Guardada en: " + destino.toAbsolutePath());
                pie.setForeground(new Color(140, 150, 174));
                pie.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                pie.setHorizontalAlignment(SwingConstants.CENTER);

                panel.add(titulo, BorderLayout.NORTH);
                panel.add(lblImagen, BorderLayout.CENTER);
                panel.add(pie, BorderLayout.SOUTH);

                frame.setContentPane(panel);
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.pack();
                frame.setMinimumSize(new Dimension(520, 420));
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            } catch (IOException e) {
                System.err.println("No se pudo mostrar la imagen: " + e.getMessage());
            }
        });
    }

    private void cabecera() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("   ADIVINA EL PERSONAJE  -  CLIENTE UDP (versión reducida)");
        System.out.println("=================================================================");
        System.out.println("   Servidor : " + host + ":" + puerto + "/udp");
        System.out.println("   Nombre   : " + nombre);
        System.out.println("-----------------------------------------------------------------");
        System.out.println();
    }

    // =======================================================================
    // main
    // =======================================================================

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, StandardCharsets.UTF_8));

        String host = UdpProtocol.DEFAULT_HOST;
        int puerto = UdpProtocol.DEFAULT_PORT;
        String nombre = "Jugador";

        if (args.length >= 1 && !args[0].trim().isEmpty()) {
            host = args[0].trim();
        }
        if (args.length >= 2) {
            try {
                puerto = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                System.err.println("ERROR: el puerto debe ser un número entero. "
                        + "Se recibió: '" + args[1] + "'");
                System.err.println("Uso: gradle :udp:runClient -Phost=localhost "
                        + "-Pport=8080 -Pname=Ana");
                System.exit(1);
            }
        }
        if (args.length >= 3 && !args[2].trim().isEmpty()) {
            nombre = args[2].trim();
        }

        if (puerto < 1 || puerto > 65535) {
            System.err.println("ERROR: el puerto debe estar entre 1 y 65535.");
            System.exit(1);
        }

        boolean ok = new UdpClient(host, puerto, nombre).ejecutar();
        if (!ok) {
            System.exit(4);
        }
    }
}
