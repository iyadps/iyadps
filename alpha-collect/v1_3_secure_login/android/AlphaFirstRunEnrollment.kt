package org.odk.collect.android.activities

import android.app.AlertDialog
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import org.odk.collect.android.R
import org.odk.collect.android.configure.qr.AppConfigurationGenerator
import org.odk.collect.android.mainmenu.MainMenuActivity
import org.odk.collect.projects.ProjectCreator
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Alpha-owned identity enrollment, without any KoboToolbox credentials in the APK.
 * First-launch only. Project server remains Alpha's fixed HTTPS gateway.
 * Secure offline session/device key handling requires follow-up integration work.
 */
class AlphaFirstRunEnrollment(
    private val activity: FirstLaunchActivity,
    private val projectCreator: ProjectCreator,
    private val config: AppConfigurationGenerator
) {
    private val prefs = activity.getSharedPreferences("alpha_secure_enrollment", 0)
    private val deviceId: String = prefs.getString("installation_id", null)
        ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_id", it).apply()
        }
    private val server: String = activity.getString(R.string.alpha_server_url).trimEnd('/')

    fun show() {
        val layout=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            val padding=(20*resources.displayMetrics.density).toInt()
            setPadding(padding,padding,padding,padding)
        }
        val username=EditText(activity).apply { hint="Alpha Collect username"; isSingleLine=true }
        val password=EditText(activity).apply {
            hint="Alpha Collect password"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(username,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(password,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog=AlertDialog.Builder(activity)
            .setTitle("Alpha Collect Secure Login")
            .setMessage("Alpha International login. No KoboToolbox account is required.")
            .setView(layout)
            .setPositiveButton("Sign in",null)
            .setNeutralButton("Request account",null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name=username.text.toString().trim()
                val secret=password.text.toString()
                if (name.isEmpty() || secret.isEmpty()) {
                    password.error="Enter both username and password"
                    return@setOnClickListener
                }
                Thread {
                    try {
                        val login=post("/v1/login",JSONObject().apply {
                            put("username",name); put("password",secret); put("device_id",deviceId)
                        })
                        val issued=post("/v1/collect-credential",JSONObject(),login.getString("access_token"))
                        if (issued.getString("server_url")!=server) {
                            throw IllegalStateException("Alpha server address mismatch")
                        }
                        val collectUsername=issued.getString("username")
                        val collectSecret=issued.getString("password")
                        activity.runOnUiThread {
                            try {
                                val settings=config.getAppConfigurationAsJsonWithServerDetails(
                                    server, collectUsername,collectSecret)
                                projectCreator.createNewProject(settings,true)
                                dialog.dismiss()
                                ActivityUtils.startActivityAndCloseAllOthers(
                                    activity,MainMenuActivity::class.java)
                            } catch (_:Exception) {
                                Toast.makeText(activity,"Cannot configure Alpha project",Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e:Exception) {
                        activity.runOnUiThread {
                            Toast.makeText(activity,e.message ?: "Sign-in failed",Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val name=username.text.toString().trim()
                val secret=password.text.toString()
                if (name.length<3 || secret.length<12) {
                    Toast.makeText(activity,"Username (3+) and password (12+) required",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Thread {
                    try {
                        post("/v1/register", JSONObject().apply {
                            put("username",name); put("password",secret); put("device_id",deviceId)
                        })
                        activity.runOnUiThread {
                            Toast.makeText(activity,"Registration pending Alpha administrator approval",Toast.LENGTH_LONG).show()
                        }
                    } catch (e:Exception) {
                        activity.runOnUiThread {
                            Toast.makeText(activity,e.message ?: "Registration failed",Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun post(path:String,payload:JSONObject,bearer:String?=null):JSONObject {
        if (!server.startsWith("https://") || server.contains(".invalid")) {
            throw IllegalStateException("Alpha HTTPS server has not been configured")
        }
        val connection=(URL(server+path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod="POST"
            connection.connectTimeout=10000
            connection.readTimeout=20000
            connection.instanceFollowRedirects=false
            connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json")
            connection.setRequestProperty("Accept","application/json")
            if (bearer!=null) connection.setRequestProperty("Authorization","Bearer $bearer")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status=connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException(
                    when (status) {
                        401 -> "Incorrect Alpha login"
                        403 -> "Awaiting account or tablet approval"
                        409 -> "Account or tablet already registered"
                        else -> "Alpha server response: $status"
                    })
            }
            val response=connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
