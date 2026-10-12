package com.example.androidaimanager

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UserSkill(
    val name: String,
    val description: String,
    val actionType: String, // "http", "shell"
    val config: JSONObject
)

class UserSkillsManager(context: Context) {
    private val skillsFile = File(context.filesDir, "user_skills.json")
    private val mutex = Mutex()
    private var cachedSkills: List<UserSkill> = emptyList()

    init {
        loadIntoMemory()
    }

    private fun loadIntoMemory() {
        if (!skillsFile.exists()) {
            cachedSkills = emptyList()
            return
        }
        val skills = mutableListOf<UserSkill>()
        try {
            val jsonArray = JSONArray(skillsFile.readText())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                skills.add(
                    UserSkill(
                        name = obj.getString("name"),
                        description = obj.optString("description", "User Skill"),
                        actionType = obj.getString("actionType"),
                        config = obj.getJSONObject("config")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        cachedSkills = skills
    }

    suspend fun getAllSkills(): List<UserSkill> = mutex.withLock {
        if (cachedSkills.isEmpty() && skillsFile.exists()) {
            loadIntoMemory()
        }
        return cachedSkills
    }

    suspend fun addSkill(skill: UserSkill) = mutex.withLock {
        val current = cachedSkills.toMutableList()
        current.removeAll { it.name.equals(skill.name, ignoreCase = true) }
        current.add(skill)

        val jsonArray = JSONArray()
        current.forEach { item ->
            val obj = JSONObject().apply {
                put("name", item.name)
                put("description", item.description)
                put("actionType", item.actionType)
                put("config", item.config)
            }
            jsonArray.put(obj)
        }
        skillsFile.writeText(jsonArray.toString(2))
        cachedSkills = current
    }

    suspend fun clearAllSkills() = mutex.withLock {
        if (skillsFile.exists()) {
            skillsFile.delete()
        }
        cachedSkills = emptyList()
    }
}