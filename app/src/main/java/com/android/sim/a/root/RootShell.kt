package com.android.sim.a.root

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

object RootShell {

    private val CANDIDATE_AT_PORTS = listOf(
        "/dev/smd0",
        "/dev/smd7",
        "/dev/smd11",
        "/dev/radio/pttycmd1",
        "/dev/radio/atci1",
        "/dev/ttyUSB0",
        "/dev/ttyUSB1",
        "/dev/ttyUSB2",
        "/dev/ttyACM0",
        "/dev/ttyC0",
        "/dev/umts_router"
    )

    fun isRootAvailable(): Boolean {
        val result = exec("id")
        return result.contains("uid=0")
    }

    fun exec(command: String, timeoutSeconds: Long = 8): String {
        val output = StringBuilder()
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            val readThread = Thread {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                    }
                    while (errReader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                    }
                } catch (_: Exception) {
                }
            }
            readThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
            }
            readThread.join(1000)
        } catch (e: Exception) {
            return "ERROR: ${e.message}"
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
        return output.toString().trim()
    }

    fun detectAtPorts(): List<String> {
        val found = mutableListOf<String>()
        val checkScript = buildString {
            for (port in CANDIDATE_AT_PORTS) {
                append("[ -e \"$port\" ] && echo \"$port\"; ")
            }
        }
        val result = exec(checkScript)
        if (result.isNotBlank() && !result.startsWith("ERROR:")) {
            result.lines()
                .map { it.trim() }
                .filter { it.startsWith("/dev/") }
                .forEach { found.add(it) }
        }
        return found
    }

    /**
     * يرسل أمر AT إلى منفذ المودم ويقرأ الاستجابة مع مهلة زمنية آمنة لأندرويد 6
     */
    fun sendAtCommand(port: String, atCommand: String, waitMs: Long = 1500): String {
        val sanitizedPort = port.trim()
        val sanitizedCmd = atCommand.trim().replace("\"", "\\\"")
        val sleepSec = String.format(Locale.US, "%.1f", waitMs / 1000.0)

        val script = """
            if [ ! -e "$sanitizedPort" ]; then
                echo "PORT_NOT_FOUND"
                exit 1
            fi
            cat "$sanitizedPort" > /data/local/tmp/sim_at_out.txt &
            CAT_PID=$!
            sleep 0.2
            printf "%s\r\n" "$sanitizedCmd" > "$sanitizedPort"
            sleep $sleepSec
            kill -9 ${'$'}CAT_PID 2>/dev/null
            cat /data/local/tmp/sim_at_out.txt 2>/dev/null
            rm -f /data/local/tmp/sim_at_out.txt
        """.trimIndent()

        return exec(script, timeoutSeconds = 6)
    }
}
