package com.example.wifiguardian

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/** A simple app-local shell console. It has no PTY or external package manager. */
class TerminalActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private lateinit var output: TextView
    private lateinit var command: EditText
    private lateinit var scroll: ScrollView
    private val transcript = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 18, 25)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.rgb(8, 18, 25))
        }
        val heading = TextView(this).apply {
            text = "WiFi Guardian terminal"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        root.addView(heading)
        root.addView(TextView(this).apply {
            text = "Android shell inside this app. Commands run with app permissions. No Termux packages, root, or other devices' traffic."
            textSize = 13f
            setTextColor(Color.LTGRAY)
        })
        scroll = ScrollView(this).apply { isFillViewport = true }
        output = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(171, 241, 181))
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val entry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        command = EditText(this).apply {
            hint = "Enter shell command"
            setSingleLine(true)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        entry.addView(command, LinearLayout.LayoutParams(0, -2, 1f))
        entry.addView(Button(this).apply { text = "Run"; setOnClickListener { submit() } })
        root.addView(entry)
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply { text = "Clear"; setOnClickListener { transcript.clear(); output.text = "" } })
        controls.addView(Button(this).apply { text = "Restart shell"; setOnClickListener { restart() } })
        controls.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
        root.addView(controls)
        setContentView(root)
        restart()
    }

    private fun append(value: String) {
        runOnUiThread {
            transcript.append(value)
            if (transcript.length > 100_000) transcript.delete(0, transcript.length - 100_000)
            output.text = transcript.toString()
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun restart() {
        io.execute {
            process?.destroy()
            try {
                val shell = ProcessBuilder("/system/bin/sh")
                    .directory(filesDir)
                    .redirectErrorStream(true)
                shell.environment()["HOME"] = filesDir.absolutePath
                shell.environment()["TMPDIR"] = cacheDir.absolutePath
                val next = shell.start()
                process = next
                input = BufferedWriter(OutputStreamWriter(next.outputStream))
                append("\nShell started in ${filesDir.absolutePath}\nTry: pwd, ls, id, ip, ping -c 4 <gateway>\n$ ")
                Thread {
                    try {
                        next.inputStream.bufferedReader().use { reader ->
                            val buffer = CharArray(2048)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                append(String(buffer, 0, count))
                            }
                        }
                    } catch (_: Exception) {
                        // Closing the activity or restarting the shell closes the stream.
                    } finally {
                        if (process === next) append("\nShell exited. Tap Restart shell to continue.\n")
                    }
                }.apply { isDaemon = true; start() }
            } catch (e: Exception) {
                append("Could not start Android shell: ${e.message}\n")
            }
        }
    }

    private fun submit() {
        val line = command.text.toString()
        if (line.isBlank()) return
        command.text.clear()
        append("$ $line\n")
        if (line == "clear") { transcript.clear(); output.text = ""; return }
        io.execute {
            try {
                val writer = input ?: error("Shell is not ready")
                if (process?.isAlive != true) error("Shell has exited")
                writer.write(line)
                writer.newLine()
                writer.write("printf '\\n$ '")
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                append("Command failed: ${e.message}\n")
            }
        }
    }

    override fun onDestroy() {
        process?.destroy()
        io.shutdownNow()
        super.onDestroy()
    }
}
