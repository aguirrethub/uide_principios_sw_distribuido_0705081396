/*
 * Archivo : UdpServer.java
 * Paquete : udp.server
 *
 * Descripcion:
 *   Servidor UDP de la version reducida. Escucha datagramas en un unico
 *   socket (en UDP no hay "accept" ni conexion por cliente) y responde:
 *
 *     NAME   -> IMAGE_INFO + N datagramas IMAGE_CHUNK con la imagen
 *     RESEND -> reenvia solo los trozos que el cliente dice no haber recibido
 *
 *   La imagen se elige a partir del nombre recibido: se toma el hash del
 *   nombre y se usa para seleccionar una de las tarjetas de personaje. Asi el
 *   mismo nombre recibe siempre la misma imagen, que es lo que pide el
 *   enunciado ("una imagen asociada al nombre").
 *
 *   Las transferencias recientes se guardan en memoria durante un rato para
 *   poder atender los RESEND sin volver a leer el disco.
 *
 * Uso:
 *   gradle :udp:runServer                -> puerto 8080
 *   gradle :udp:runServer -Pport=9099
 *   java udp.server.UdpServer [puerto]
 */

package udp.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import udp.common.UdpMessage;
import udp.common.UdpProtocol;

/**
 * Servidor UDP: recibe un nombre y devuelve una imagen fragmentada.
 */
public class UdpServer {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Imágenes que puede devolver el servidor, elegidas segun el nombre. */
    private static final String[] CATALOGO = {
        "img/characters/vader_reveal.png",
        "img/characters/forrest_reveal.png",
        "img/characters/vito_reveal.png",
        "img/characters/rocky_reveal.png",
        "img/characters/sparrow_reveal.png",
        "img/characters/dorothy_reveal.png",
        "img/characters/t800_reveal.png",
        "img/characters/woody_reveal.png"
    };

    /** Imagen de respaldo si no se encuentra ninguna del catalogo. */
    private static final String IMAGEN_POR_DEFECTO = "img/udp_default.png";

    /** Cuantas transferencias recientes se recuerdan para atender RESEND. */
    private static final int MAX_TRANSFERENCIAS = 32;

    private final int puerto;
    private DatagramSocket socket;

    /** Cache ruta -> Base64, para no releer el disco en cada peticion. */
    private final Map<String, String> cacheImagenes = new ConcurrentHashMap<>();

    /**
     * Transferencias en curso o recientes: id -> Base64 de la imagen.
     * LinkedHashMap en modo LRU para no crecer sin limite.
     */
    private final Map<String, String> transferencias =
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_TRANSFERENCIAS;
                }
            };

    public UdpServer(int puerto) {
        this.puerto = puerto;
    }

    // =======================================================================
    // Arranque
    // =======================================================================

    /** Abre el socket y entra en el bucle de recepcion. */
    public void iniciar() {
        try {
            socket = new DatagramSocket(new InetSocketAddress("0.0.0.0", puerto));
            socket.setSendBufferSize(UdpProtocol.SOCKET_BUFFER);
            socket.setReceiveBufferSize(UdpProtocol.SOCKET_BUFFER);
        } catch (BindException e) {
            System.err.println("ERROR: el puerto UDP " + puerto + " ya está en uso.");
            System.exit(2);
            return;
        } catch (IOException e) {
            System.err.println("ERROR: no se pudo abrir el socket UDP: " + e.getMessage());
            System.exit(2);
            return;
        }

        banner();

        byte[] buffer = new byte[UdpProtocol.BUFFER_SIZE];
        while (true) {
            DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(paquete);
                atender(paquete);
            } catch (IOException e) {
                log("Error recibiendo un datagrama: " + e.getMessage());
            } catch (Exception e) {
                // Un datagrama basura nunca debe tumbar el servidor.
                log("Error inesperado: " + e);
            }
        }
    }

    private void banner() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("   ADIVINA EL PERSONAJE  -  SERVIDOR UDP (versión reducida)");
        System.out.println("=================================================================");
        System.out.println("   Puerto         : " + puerto + "/udp");
        System.out.println("   Protocolo      : JSON v" + UdpProtocol.VERSION
                + " sobre datagramas");
        System.out.println("   Fragmentación  : " + UdpProtocol.CHUNK_SIZE
                + " caracteres Base64 por datagrama");
        System.out.println("   Imágenes       : " + CATALOGO.length + " en catálogo");
        System.out.println("-----------------------------------------------------------------");
        System.out.println("   Esperando datagramas... (Ctrl+C para detener)");
        System.out.println("=================================================================");
        System.out.println();
    }

    // =======================================================================
    // Atencion de peticiones
    // =======================================================================

    private void atender(DatagramPacket paquete) {
        InetAddress origen = paquete.getAddress();
        int puertoOrigen = paquete.getPort();

        UdpMessage peticion;
        try {
            peticion = UdpMessage.parse(paquete);
        } catch (JSONException e) {
            log("Datagrama inválido desde " + origen.getHostAddress() + ":" + puertoOrigen
                    + " -> " + e.getMessage());
            responder(UdpMessage.error(UdpProtocol.ERR_BAD_JSON,
                    "El datagrama no contiene JSON válido: " + e.getMessage()),
                    origen, puertoOrigen);
            return;
        }

        switch (peticion.getType()) {
            case UdpProtocol.REQ_NAME:
                atenderName(peticion, origen, puertoOrigen);
                break;
            case UdpProtocol.REQ_RESEND:
                atenderResend(peticion, origen, puertoOrigen);
                break;
            default:
                log("Tipo desconocido '" + peticion.getType() + "' desde "
                        + origen.getHostAddress());
                responder(UdpMessage.error(UdpProtocol.ERR_UNKNOWN_TYPE,
                        "Tipo de mensaje desconocido: '" + peticion.getType()
                        + "'. Tipos válidos: NAME, RESEND."), origen, puertoOrigen);
                break;
        }
    }

    /** NAME: valida el nombre, elige la imagen y la envia troceada. */
    private void atenderName(UdpMessage peticion, InetAddress origen, int puertoOrigen) {
        String nombre = peticion.optString(UdpProtocol.P_NAME, "").trim();

        if (nombre.isEmpty()) {
            log("NAME sin nombre desde " + origen.getHostAddress());
            responder(UdpMessage.error(UdpProtocol.ERR_INVALID_NAME,
                    "Debes enviar un nombre no vacío en payload.name"),
                    origen, puertoOrigen);
            return;
        }
        if (nombre.length() > 40) {
            nombre = nombre.substring(0, 40);
        }

        String ruta = elegirImagen(nombre);
        String base64 = cargarImagen(ruta);

        if (base64.isEmpty()) {
            log("No se pudo cargar ninguna imagen para '" + nombre + "'");
            responder(UdpMessage.error(UdpProtocol.ERR_NO_IMAGE,
                    "El servidor no encontró ninguna imagen para enviar."),
                    origen, puertoOrigen);
            return;
        }

        String transferId = UUID.randomUUID().toString().substring(0, 8);
        int totalChunks = UdpProtocol.numeroDeChunks(base64.length());

        synchronized (transferencias) {
            transferencias.put(transferId, base64);
        }

        log("NAME '" + nombre + "' desde " + origen.getHostAddress() + ":" + puertoOrigen
                + " -> " + nombreCorto(ruta) + " (" + totalChunks + " trozos, "
                + base64.length() + " chars)");

        // 1) Aviso de lo que viene
        responder(UdpMessage.of(UdpProtocol.RES_IMAGE_INFO)
                .put(UdpProtocol.P_TRANSFER_ID, transferId)
                .put(UdpProtocol.P_CHUNKS, totalChunks)
                .put(UdpProtocol.P_BYTES, base64.length())
                .put(UdpProtocol.P_IMAGE_FORMAT, "png")
                .put(UdpProtocol.P_IMAGE_NAME, nombreCorto(ruta))
                .put(UdpProtocol.P_NAME, nombre)
                .put(UdpProtocol.P_MESSAGE, "¡Hola " + nombre
                        + "! Aquí tienes tu imagen, enviada en " + totalChunks
                        + " datagramas."), origen, puertoOrigen);

        // 2) Los trozos
        for (int seq = 0; seq < totalChunks; seq++) {
            enviarChunk(transferId, base64, seq, origen, puertoOrigen);
        }
    }

    /** RESEND: reenvia unicamente los trozos que el cliente pide. */
    private void atenderResend(UdpMessage peticion, InetAddress origen, int puertoOrigen) {
        String transferId = peticion.optString(UdpProtocol.P_TRANSFER_ID, "");
        String base64;
        synchronized (transferencias) {
            base64 = transferencias.get(transferId);
        }

        if (base64 == null) {
            log("RESEND de una transferencia desconocida: '" + transferId + "'");
            responder(UdpMessage.error(UdpProtocol.ERR_UNKNOWN_TRANSFER,
                    "La transferencia '" + transferId + "' ya no está disponible. "
                    + "Vuelve a enviar NAME."), origen, puertoOrigen);
            return;
        }

        JSONArray faltantes = peticion.getPayload().optJSONArray(UdpProtocol.P_MISSING);
        if (faltantes == null || faltantes.length() == 0) {
            return;
        }

        log("RESEND de " + faltantes.length() + " trozo(s) para la transferencia "
                + transferId);
        for (int i = 0; i < faltantes.length(); i++) {
            enviarChunk(transferId, base64, faltantes.optInt(i, -1), origen, puertoOrigen);
        }
    }

    /** Envia un trozo concreto, con una micropausa para no saturar al receptor. */
    private void enviarChunk(String transferId, String base64, int seq,
                             InetAddress origen, int puertoOrigen) {
        if (seq < 0) {
            return;
        }
        String datos = UdpProtocol.chunk(base64, seq);
        if (datos.isEmpty()) {
            return;
        }
        responder(UdpMessage.of(UdpProtocol.RES_IMAGE_CHUNK)
                .put(UdpProtocol.P_TRANSFER_ID, transferId)
                .put(UdpProtocol.P_SEQ, seq)
                .put(UdpProtocol.P_CHUNKS, UdpProtocol.numeroDeChunks(base64.length()))
                .put(UdpProtocol.P_DATA, datos), origen, puertoOrigen);

        // Sin esta pausa, una ráfaga de decenas de datagramas puede desbordar
        // el buffer del receptor y provocar pérdidas evitables.
        if (UdpProtocol.PAUSA_ENTRE_CHUNKS_US > 0) {
            try {
                Thread.sleep(0, UdpProtocol.PAUSA_ENTRE_CHUNKS_US * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void responder(UdpMessage mensaje, InetAddress origen, int puertoOrigen) {
        try {
            socket.send(mensaje.toPacket(origen, puertoOrigen));
        } catch (IOException e) {
            log("No se pudo enviar " + mensaje.getType() + ": " + e.getMessage());
        }
    }

    // =======================================================================
    // Imagenes
    // =======================================================================

    /**
     * Elige la imagen a partir del nombre: siempre la misma para el mismo
     * nombre, pero repartida entre todo el catalogo.
     */
    private String elegirImagen(String nombre) {
        int hash = Math.abs(nombre.toLowerCase().hashCode());
        return CATALOGO[hash % CATALOGO.length];
    }

    /** Carga la imagen (classpath o disco) y la devuelve en Base64, con cache. */
    private String cargarImagen(String ruta) {
        String cacheada = cacheImagenes.get(ruta);
        if (cacheada != null) {
            return cacheada;
        }

        byte[] datos = leerRecurso(ruta);
        if (datos == null) {
            datos = leerRecurso(IMAGEN_POR_DEFECTO);
        }
        if (datos == null) {
            return "";
        }

        String base64 = Base64.getEncoder().encodeToString(datos);
        cacheImagenes.put(ruta, base64);
        return base64;
    }

    private byte[] leerRecurso(String ruta) {
        // 1) classpath
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = UdpServer.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(ruta)) {
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } catch (IOException ignored) {
            // Se intenta en disco.
        }

        // 2) disco, relativo a varias raices posibles
        List<String> raices = new ArrayList<>();
        raices.add("");
        raices.add("..");
        raices.add("../..");
        for (String raiz : raices) {
            try {
                Path p = raiz.isEmpty() ? Paths.get(ruta) : Paths.get(raiz, ruta);
                if (Files.isReadable(p)) {
                    return Files.readAllBytes(p);
                }
            } catch (Exception ignored) {
                // Se prueba la siguiente raiz.
            }
        }
        return null;
    }

    private String nombreCorto(String ruta) {
        int i = ruta.lastIndexOf('/');
        return i >= 0 ? ruta.substring(i + 1) : ruta;
    }

    private void log(String texto) {
        System.out.println("[" + LocalTime.now().format(HORA) + "] " + texto);
    }

    // =======================================================================
    // main
    // =======================================================================

    public static void main(String[] args) {
        int puerto = UdpProtocol.DEFAULT_PORT;

        if (args.length >= 1) {
            try {
                puerto = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                System.err.println("ERROR: el puerto debe ser un número entero. "
                        + "Se recibió: '" + args[0] + "'");
                System.err.println("Uso: gradle :udp:runServer -Pport=8080");
                System.exit(1);
            }
            if (puerto < 1 || puerto > 65535) {
                System.err.println("ERROR: el puerto debe estar entre 1 y 65535.");
                System.exit(1);
            }
        } else {
            System.out.println("(sin argumentos: se usa el puerto por defecto "
                    + UdpProtocol.DEFAULT_PORT + ")");
        }

        // UTF-8 explicito para que los acentos salgan bien en cualquier consola.
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, StandardCharsets.UTF_8));

        new UdpServer(puerto).iniciar();
    }
}
