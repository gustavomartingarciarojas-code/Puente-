package ar.trainingwraps.puente

import android.content.Context
import android.net.ConnectivityManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

/** Mensajes que viajan entre la tablet (emisor) y el celular (receptor). */
object Protocolo {
    const val PUERTO = 47800
    const val MAGIA = 0x50554E54 // "PUNT"
    const val VERSION = 1
    const val TIPO_NSD = "_puente._tcp"

    // tablet -> celular
    const val HOLA: Byte = 1
    const val ENTRAR: Byte = 10   // float: altura relativa (0..1) por donde entra el puntero
    const val MOVER: Byte = 11    // float dx, float dy
    const val BOTON: Byte = 12    // int botón, boolean apretado
    const val RUEDA: Byte = 13    // float vertical, float horizontal
    const val TECLA: Byte = 14    // int keyCode, int metaState
    const val TEXTO: Byte = 15    // String
    const val SOLTAR: Byte = 16   // la tablet recupera el control
    const val PING: Byte = 17

    // celular -> tablet
    const val OK: Byte = 2        // int ancho, int alto
    const val ERROR: Byte = 3     // String
    const val SALIR: Byte = 20    // float: altura relativa por donde sale el puntero

    const val BOTON_IZQ = 1
    const val BOTON_DER = 2
    const val BOTON_MEDIO = 3
    const val BOTON_ATRAS = 4
    const val BOTON_ADELANTE = 5

    fun msg(tipo: Byte, cuerpo: (DataOutputStream) -> Unit = {}): ByteArray {
        val b = ByteArrayOutputStream(32)
        val d = DataOutputStream(b)
        d.writeByte(tipo.toInt())
        cuerpo(d)
        d.flush()
        return b.toByteArray()
    }
}

/** Socket con un hilo propio de escritura: se puede enviar desde cualquier hilo sin bloquear. */
class Conexion(private val socket: Socket) {
    private val cola = LinkedBlockingQueue<ByteArray>()
    private val salida = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    val entrada = DataInputStream(BufferedInputStream(socket.getInputStream()))

    @Volatile var abierta = true
        private set

    private val escritor = Thread {
        try {
            var seguir = true
            while (seguir && abierta) {
                var m: ByteArray? = cola.take()
                while (m != null) {
                    if (m.isEmpty()) { seguir = false; break }
                    salida.write(m)
                    m = cola.poll()
                }
                salida.flush()
            }
        } catch (_: Exception) {
        }
        cerrar()
    }

    init {
        socket.tcpNoDelay = true
        escritor.isDaemon = true
        escritor.name = "puente-escritor"
        escritor.start()
    }

    fun enviar(m: ByteArray) {
        if (abierta) cola.offer(m)
    }

    fun cerrar() {
        if (!abierta) return
        abierta = false
        cola.offer(ByteArray(0))
        try { socket.close() } catch (_: Exception) { }
    }
}

/** Preferencias guardadas en el equipo. */
class Ajustes(ctx: Context) {
    private val p = ctx.getSharedPreferences("puente", Context.MODE_PRIVATE)

    /** 0 = automático (por tamaño de pantalla), 1 = tablet, 2 = celular */
    var modo: Int
        get() = p.getInt("modo", 0)
        set(v) = p.edit().putInt("modo", v).apply()

    var ip: String
        get() = p.getString("ip", "") ?: ""
        set(v) = p.edit().putString("ip", v.trim()).apply()

    var pinRemoto: String
        get() = p.getString("pin_remoto", "") ?: ""
        set(v) = p.edit().putString("pin_remoto", v.trim()).apply()

    /** 0 = el celular está a la DERECHA de la tablet, 1 = a la IZQUIERDA */
    var lado: Int
        get() = p.getInt("lado", 0)
        set(v) = p.edit().putInt("lado", v).apply()

    var sensibilidad: Float
        get() = p.getFloat("sensibilidad", 1.6f)
        set(v) = p.edit().putFloat("sensibilidad", v).apply()

    /** PIN de este equipo cuando funciona como celular receptor. */
    val pinPropio: String
        get() {
            var pin = p.getString("pin_propio", null)
            if (pin == null) {
                pin = Random.nextInt(100000, 1000000).toString()
                p.edit().putString("pin_propio", pin).apply()
            }
            return pin
        }
}

object Red {
    /** Direcciones IPv4 de este equipo en la red local (Wi-Fi o zona Wi-Fi). */
    fun ipsLocales(ctx: Context): List<String> {
        val res = LinkedHashSet<String>()
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)!!
            cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.forEach {
                val a = it.address
                if (a is Inet4Address && !a.isLoopbackAddress) res.add(a.hostAddress ?: "")
            }
        } catch (_: Exception) { }
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) res.add(a.hostAddress ?: "")
                }
            }
        } catch (_: Exception) { }
        res.remove("")
        return res.toList()
    }
}
