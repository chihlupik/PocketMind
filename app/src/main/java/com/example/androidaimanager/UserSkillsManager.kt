package com.example.androidaimanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UserSkill(
    val name: String,
    val description: String,
    val actionType: String, // "http", "shell", "intent"
    val config: JSONObject  // url, method, body_template, cmd, package, extra_text
)

class UserSkillsManager(context: Context) {
    private val skillsFile = File(context.filesDir, "user_skills.json")

    fun getAllSkills(): List<UserSkill> {
        if (!skillsFile.exists()) return emptyList()
        val skills = mutableListOf<UserSkill>()
        try {
            val jsonArray = JSONArray(skillsFile.readText())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                skills.add(
                    UserSkill(
                        name = obj.getString("name"),
                        description = obj.getString("description"),
                        actionType = obj.getString("actionType"),
                        config = obj.getJSONObject("config")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return skills
    }

    fun addSkill(skill: UserSkill) {
        val current = getAllSkills().toMutableList()
        current.removeAll { it.name == skill.name } // Перезаписываем, если навык с таким именем уже есть
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
    }
}