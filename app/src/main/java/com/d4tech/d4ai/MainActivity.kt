package com.d4tech.d4ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.d4tech.d4ai.ai.PollinationsClient
import com.d4tech.d4ai.commands.CommandRouter
import com.d4tech.d4ai.commands.RouteResult
import com.d4tech.d4ai.voice.D4AiListenService
import com.d4tech.d4ai.voice.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ChatMessage(val role: String, val text: String)

class MainActivity : AppCompatActivity() {

    private lateinit var chatList: RecyclerView
    private lateinit var input: EditText
    private lateinit var statusLine: TextView
    private lateinit var listenBanner: TextView
    private lateinit var btnAuto: Button
    private lateinit var adapter: ChatAdapter
    private lateinit var speech: SpeechEngine
    private lateinit var router: CommandRouter
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val messages = mutableListOf<ChatMessage>()
    private var autoTalk = true
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        PollinationsClient.init(applicationContext)
        router = CommandRouter(applicationContext)

        chatList = findViewById(R.id.chatList)
        input = findViewById(R.id.input)
        statusLine = findViewById(R.id.statusLine)
        listenBanner = findViewById(R.id.listenBanner)
        btnAuto = findViewById(R.id.btnAuto)

        adapter = ChatAdapter(messages)
        chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatList.adapter = adapter

        speech = SpeechEngine(
            context = this,
            onHeard = { text -> runOnUiThread { send(text) } },
            onListening = { on ->
                runOnUiThread {
                    listenBanner.visibility = if (on) View.VISIBLE else View.GONE
                    listenBanner.text = if (on) "D4AI listening…" else ""
                    statusLine.text = if (on) "Listening…" else "Online · v1.1"
                }
            },
        )
        speech.init { }

        findViewById<ImageButton>(R.id.btnSend).setOnClickListener { send(input.text.toString()) }
        findViewById<ImageButton>(R.id.btnMic).setOnClickListener {
            ensureMic()
            speech.startListening()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send(input.text.toString()); true
            } else false
        }
        btnAuto.setOnClickListener {
            autoTalk = !autoTalk
            btnAuto.text = if (autoTalk) "Auto-talk On" else "Auto-talk"
        }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        if (messages.isEmpty()) {
            append(
                "ai",
                "Hey! I'm D4AI — your personal Android assistant by D4TECH.\n\n" +
                    "Ask anything, or try commands like: open WhatsApp, volume up, flashlight, " +
                    "what is my phone storage. Say sleep to stop listening.",
            )
        }

        // Assist should not open app; if launched normally, OK
        ensureMic()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Wake D4AI (background)")
            menu.add("Sleep D4AI")
            menu.add("Set as assistant")
            menu.add("AI API key (unlimited)")
            menu.add("Clear chat")
            menu.add("About")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Wake D4AI (background)" -> wakeBackground()
                    "Sleep D4AI" -> sleepBackground()
                    "Set as assistant" -> {
                        try {
                            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                    "AI API key (unlimited)" -> promptApiKey()
                    "Clear chat" -> {
                        messages.clear()
                        adapter.notifyDataSetChanged()
                    }
                    "About" -> append(
                        "ai",
                        "D4AI by D4TECH — Creating Solutions, Innovating Lives.\nCreator: Amurun Destiny O.",
                    )
                }
                true
            }
            show()
        }
    }

    private fun promptApiKey() {
        val edit = EditText(this).apply {
            hint = "sk_… from enter.pollinations.ai"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#0b1a33"))
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Pollinations API key")
            .setMessage("Free key from enter.pollinations.ai removes rate limits.")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                PollinationsClient.setApiKey(this, edit.text.toString())
                append("ai", "API key saved.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wakeBackground() {
        ensureMic()
        val i = Intent(this, D4AiListenService::class.java).apply {
            action = D4AiListenService.ACTION_WAKE
        }
        try {
            ContextCompat.startForegroundService(this, i)
        } catch (_: Exception) {
            startService(i)
        }
        append("ai", "Waking in background — D4AI listening.")
    }

    private fun sleepBackground() {
        startService(
            Intent(this, D4AiListenService::class.java).apply {
                action = D4AiListenService.ACTION_SLEEP
            },
        )
        speech.stopListening()
        append("ai", "Sleeping.")
    }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        busy = true
        input.setText("")
        append("user", text)
        scope.launch {
            try {
                when (val r = router.handle(text)) {
                    is RouteResult.Spoke -> {
                        if (r.message == "__SLEEP__") {
                            sleepBackground()
                            append("ai", "Going to sleep.")
                        } else {
                            append("ai", r.message)
                            if (autoTalk) speech.speak(r.message, listenAfter = false)
                        }
                    }
                    is RouteResult.NeedConfirm -> {
                        append("ai", r.prompt)
                        if (autoTalk) speech.speak(r.prompt, listenAfter = true)
                    }
                    RouteResult.NotCommand -> {
                        val answer = PollinationsClient.ask(text)
                        append("ai", answer)
                        if (autoTalk) speech.speak(answer, listenAfter = false)
                    }
                }
            } catch (e: Exception) {
                append("ai", e.message ?: "Something went wrong.")
            }
            busy = false
        }
    }

    private fun append(role: String, text: String) {
        messages.add(ChatMessage(role, text))
        adapter.notifyItemInserted(messages.size - 1)
        chatList.scrollToPosition(messages.size - 1)
    }

    private fun ensureMic() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 10)
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }
}

class ChatAdapter(private val items: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: TextView = view.findViewById(R.id.bubble)
        val root: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.bubble.text = m.text
        val user = m.role == "user"
        val bg = GradientDrawable().apply {
            cornerRadius = 36f
            setColor(if (user) Color.parseColor("#2563eb") else Color.parseColor("#0f2744"))
        }
        holder.bubble.background = bg
        val lp = holder.bubble.layoutParams as ViewGroup.MarginLayoutParams
        (holder.root as android.widget.LinearLayout).gravity =
            if (user) Gravity.END else Gravity.START
        holder.bubble.layoutParams = lp
    }
}
