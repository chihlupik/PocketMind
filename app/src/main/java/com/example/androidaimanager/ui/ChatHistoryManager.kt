package com.example.androidaimanager

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Chat",
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val timestamp: Long = System.currentTimeMillis()
)

class ChatHistoryManager(context: Context) {
    private val historyFile = File(context.filesDir, "chat_history.json")
    private val mutex = Mutex()
    private var cachedSessions: List<ChatSession>? = null

    private fun loadIntoMemory(): List<ChatSession> {
        if (!historyFile.exists()) return emptyList()
        val sessions = mutableListOf<ChatSession>()
        try {
            val jsonArray = JSONArray(historyFile.readText())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val session = ChatSession(
                    id = obj.getString("id"),
                    title = obj.optString("title", "Chat"),
                    timestamp = obj.optLong("timestamp", 0)
                )
                val msgsArray = obj.getJSONArray("messages")
                for (j in 0 until msgsArray.length()) {
                    val msgObj = msgsArray.getJSONObject(j)
                    session.messages.add(
                        ChatMessage(
                            role = ChatRole.valueOf(msgObj.getString("role")),
                            text = msgObj.getString("text")
                        )
                    )
                }
                sessions.add(session)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return sessions.sortedByDescending { it.timestamp }
    }

    suspend fun getAllSessions(): List<ChatSession> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (cachedSessions == null) cachedSessions = loadIntoMemory()
            cachedSessions ?: emptyList()
        }
    }

    suspend fun saveSession(session: ChatSession) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = (cachedSessions ?: emptyList()).toMutableList()
            val index = current.indexOfFirst { it.id == session.id }
            if (index >= 0) current[index] = session else current.add(session)

            val sorted = current.sortedByDescending { it.timestamp }
            val jsonArray = JSONArray()
            sorted.forEach { s ->
                val obj = JSONObject().apply {
                    put("id", s.id); put("title", s.title); put("timestamp", s.timestamp)
                    val msgs = JSONArray()
                    s.messages.forEach { m ->
                        msgs.put(JSONObject().apply { put("role", m.role.name); put("text", m.text) })
                    }
                    put("messages", msgs)
                }
                jsonArray.put(obj)
            }
            historyFile.writeText(jsonArray.toString(2))
            cachedSessions = sorted
        }
    }

    suspend fun deleteSessions(sessionIds: List<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = (cachedSessions ?: emptyList()).toMutableList()
            current.removeAll { it.id in sessionIds }
            cachedSessions = current
            saveSessionsToFile(current)
        }
    }

    suspend fun clearAllSessions() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (historyFile.exists()) historyFile.delete()
            cachedSessions = emptyList()
        }
    }

    private fun saveSessionsToFile(sessions: List<ChatSession>) {
        val jsonArray = JSONArray()
        sessions.forEach { s ->
            val obj = JSONObject().apply {
                put("id", s.id); put("title", s.title); put("timestamp", s.timestamp)
                val msgs = JSONArray()
                s.messages.forEach { m ->
                    msgs.put(JSONObject().apply { put("role", m.role.name); put("text", m.text) })
                }
                put("messages", msgs)
            }
            jsonArray.put(obj)
        }
        historyFile.writeText(jsonArray.toString(2))
    }
}