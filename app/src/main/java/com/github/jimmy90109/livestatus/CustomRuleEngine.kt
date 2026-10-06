package com.github.jimmy90109.livestatus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import java.io.InputStreamReader

object CustomRuleEngine {
    private const val TAG = "CustomRuleEngine"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isLoaded = false
    private val rules = mutableListOf<RuleEntry>()

    data class RuleEntry(
        val packageName: String,
        val regex: Regex,
        val pillTextTemplate: String,
        val iconName: String,
        val timeoutSeconds: Int
    )

    data class MatchResult(
        val pillText: String,
        val iconName: String,
        val timeoutSeconds: Int
    )

    private fun showToastError(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, "Rules Error: $message", Toast.LENGTH_LONG).show()
        }
    }

    @Synchronized
    fun loadRules(context: Context) {
        if (isLoaded) return
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("custom_rules.json")
            val jsonString = InputStreamReader(inputStream).use { it.readText() }

            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pkg = obj.getString("package_name")
                val regexPattern = obj.getString("regex")
                val pill = obj.optString("pill_text", "")
                val icon = obj.optString("icon_name", "ic_capsule_search")
                val timeout = obj.optInt("timeout_seconds", 5)

                try {
                    val compiledRegex = Regex(regexPattern)
                    rules.add(RuleEntry(pkg, compiledRegex, pill, icon, timeout))
                } catch (regexEx: Exception) {
                    val err = "Regex error in rule #$i ($pkg): ${regexEx.localizedMessage}"
                    Log.e(TAG, err, regexEx)
                    showToastError(context, err)
                }
            }
            isLoaded = true
        } catch (e: Exception) {
            val err = "Failed reading custom_rules.json: ${e.localizedMessage}"
            Log.e(TAG, err, e)
            showToastError(context, err)
        }
    }

    fun evaluate(context: Context, packageName: String, rawText: String): MatchResult? {
        if (!isLoaded) {
            loadRules(context)
        }

        for (rule in rules) {
            if (rule.packageName.equals(packageName, ignoreCase = true)) {
                try {
                    val match = rule.regex.find(rawText)
                    if (match != null) {
                        var pill = rule.pillTextTemplate
                        match.groupValues.forEachIndexed { index, value ->
                            pill = pill.replace("$$index", value)
                        }
                        return MatchResult(
                            pillText = if (pill.isNotBlank()) pill else match.value,
                            iconName = rule.iconName,
                            timeoutSeconds = rule.timeoutSeconds
                        )
                    }
                } catch (evalEx: Exception) {
                    val err = "Eval error on ${rule.packageName}: ${evalEx.localizedMessage}"
                    Log.e(TAG, err, evalEx)
                    showToastError(context, err)
                }
            }
        }
        return null
    }
}
