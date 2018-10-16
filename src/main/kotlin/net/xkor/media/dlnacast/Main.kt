package net.xkor.media.dlnacast

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(
            "Available commands: " +
                    "\tserver: Run application as server" +
                    "\tstart: Run server in as new process and return" +
                    "\tstop: Stop server"
        )
    }

    when (args[0]) {
        "server", null -> {
            val addr = args.getOrNull(1)?.split(':').orEmpty()
            when (addr.size) {
                1 -> Server.execute(addr[0])
                2 -> if (addr[0].isNotEmpty()) {
                    Server.execute(addr[0], addr[1].toInt())
                } else {
                    Server.execute(port = addr[1].toInt())
                }
                else -> Server.execute()
            }
        }
        "start" -> startServer(args)
        "stop" -> stopServer()
    }
}

fun startServer(args: Array<String>) {

}

fun stopServer() {
    val client = HttpClient()
    runBlocking {

        //        if(client.call("http://127.0.0.1:/stop").response.status){
//
//        }
    }
}
