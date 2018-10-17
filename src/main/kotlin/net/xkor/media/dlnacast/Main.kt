package net.xkor.media.dlnacast

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.config.tryGetString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ConnectException
import java.util.concurrent.TimeUnit

object Main {

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.getOrNull(0)) {
            "start" -> startServer()
            "stop" -> stopServer()
            else -> Server.execute(args)
        }
    }

    private fun startServer() {
        exec(Main::class.java)
    }

    private fun stopServer() {
        val config = ConfigFactory.parseFile(File("application.conf"))
        val host = (config.tryGetString("ktor.deployment.host") ?: "0.0.0.0")
            .replace("0.0.0.0", "127.0.0.1")
        val port = config.tryGetString("ktor.deployment.port") ?: "8686"

        val client = HttpClient()
        runBlocking {
            try {
                client.call("http://$host:$port/stop").response
                delay(2, TimeUnit.SECONDS)
                println("Stopped")
            } catch (e: ConnectException) {
                println("Already stopped")
            }
        }
    }

    private fun exec(klass: Class<*>): Process {
        val javaHome = System.getProperty("java.home")
        val javaBin = javaHome +
                File.separator + "bin" +
                File.separator + "java"
        val classpath = System.getProperty("java.class.path")
        val className = klass.canonicalName

        val builder = ProcessBuilder(
            javaBin, "-cp", classpath, className
        )

        return builder.start()
    }

}
