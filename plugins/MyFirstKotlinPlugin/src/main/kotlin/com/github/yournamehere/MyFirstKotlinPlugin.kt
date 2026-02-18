package com.github.yournamehere

import android.content.Context
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.MessageEmbedBuilder
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.wrappers.embeds.MessageEmbedWrapper.Companion.title
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.user.CoreUser
import com.discord.stores.StoreUserTyping
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry

// Aliucord Plugin annotation. Must be present on the main class of your plugin
// Plugin class. Must extend Plugin and override start and stop
// Learn more: https://github.com/Aliucord/documentation/blob/main/plugin-dev/1_introduction.md#basic-plugin-structure
/**
 * Plugin Name: Modern Friend System
 * Author: Raz (Instagram: yaboyraz)
 */
@AliucordPlugin(
    requiresRestart = false, // Whether your plugin requires a restart after being installed/updated
)
@Suppress("unused")
class ModernFriendSystemPlugin : Plugin() {
    private val pendingCaptcha = mutableMapOf<String, Map<String, String>>()

    private fun submitPendingCaptchaInternal(context: Context, key: String, captchaToken: String, providedToken: String?): String {
        // resolve auth token
        var token = providedToken
        if (token.isNullOrEmpty()) token = settings.getString("addfriend_token", null)
        if (token.isNullOrEmpty()) {
            try {
                val storeAuth = Class.forName("com.discord.stores.StoreStream")
                    .getMethod("getAuthentication")
                    .invoke(null)
                val field = storeAuth.javaClass.getDeclaredField("token")
                field.isAccessible = true
                token = field.get(storeAuth) as? String
            } catch (e: Exception) {
                token = null
            }
        }
        if (token.isNullOrEmpty()) return "No token found."

        val data = pendingCaptcha[key] ?: return "No pending captcha data for $key"

        val payloadObj = org.json.JSONObject()
        payloadObj.put("username", key)
        payloadObj.put("captcha_key", captchaToken)
        if (!data["captcha_rqtoken"].isNullOrEmpty()) payloadObj.put("captcha_rqtoken", data["captcha_rqtoken"])
        if (!data["captcha_rqdata"].isNullOrEmpty()) payloadObj.put("captcha_rqdata", data["captcha_rqdata"])

        return try {
            val response = com.aliucord.Http.Request("https://discord.com/api/v9/users/@me/relationships", "POST")
                .setHeader("Authorization", token)
                .setHeader("Content-Type", "application/json")
                .executeWithBody(payloadObj.toString())
            val code = response.statusCode
            val body = response.text()
            if (code in 200..299) {
                pendingCaptcha.remove(key)
                "Success: Friend request completed for $key"
            } else {
                "Error $code: $body"
            }
        } catch (e: Exception) {
            "Request failed: ${e.message}"
        }
    }

    private fun openCaptchaUI(context: Context, key: String, sitekey: String) {
        val html = """
                <html>
                  <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
                    <style>body{margin:0;padding:0;height:100vh}#hcaptcha{display:flex;justify-content:center;align-items:center;height:100vh}</style>
                  </head>
                  <body>
                    <div id="hcaptcha"></div>
                    <script>
                      function onSuccess(token){
                        Android.onToken(token);
                      }
                      function onLoad(){
                        hcaptcha.render('hcaptcha', {sitekey: '${sitekey}', callback: onSuccess});
                      }
                      window.onload = onLoad;
                    </script>
                  </body>
                </html>
            """.trimIndent()

        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.webViewClient = WebViewClient()
                webView.webChromeClient = WebChromeClient()

                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onToken(token: String) {
                        val res = submitPendingCaptchaInternal(context, key, token, null)
                        Handler(Looper.getMainLooper()).post {
                            try {
                                AlertDialog.Builder(context)
                                    .setTitle("hCaptcha Result")
                                    .setMessage(res)
                                    .setPositiveButton("OK", null)
                                    .show()
                            } catch (_: Exception) {}
                        }
                    }
                }, "Android")

                val dialog = AlertDialog.Builder(context)
                    .setTitle("Solve hCaptcha for $key")
                    .setView(webView)
                    .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                    .setCancelable(true)
                    .create()

                dialog.show()
                webView.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null)
            } catch (e: Exception) {
                try {
                    AlertDialog.Builder(context)
                        .setTitle("hCaptcha")
                        .setMessage("Failed to open in-app captcha UI: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                } catch (_: Exception) {}
            }
        }
    }
    override fun start(context: Context) {
        // Register profile action and attempt to patch built-in Add Friend UI
        try {
            registerProfileAction(context)
        } catch (_: Exception) {}
        try {
            patchBuiltInAddFriendFlow()
        } catch (_: Exception) {}

        // Command to set Discord token for /add-friend
        commands.registerCommand(
            "set-addfriend-token",
            "Set Discord token for /add-friend command",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "token",
                    "Your Discord token",
                ),
            ),
        ) { ctx ->
            val token = ctx.getString("token")?.trim()
            if (token.isNullOrEmpty()) {
                return@registerCommand CommandsAPI.CommandResult("Please provide a token.")
            }
            settings.setString("addfriend_token", token)
            CommandsAPI.CommandResult("Token saved. You can now use /add-friend.")
        }

        // Continue plugin startup
        // Register a command with the name hello and description "My first command!" and no arguments.
        // Learn more: https://github.com/Aliucord/documentation/blob/main/plugin-dev/2_commands.md
        commands.registerCommand("hello", "My first command!") {
            // Just return a command result with hello world as the content
            CommandsAPI.CommandResult(
                "Hello World!",
                null, // List of embeds
                false, // Whether to send visible for everyone
            )
        }

        // /add-friend command for new Discord username system (stealth, no Aliucord utils)
        commands.registerCommand(
            "add-friend",
            "Send a friend request by username (new Discord system)",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "username",
                    "Username (e.g. user or user@unique)",
                ),
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "token",
                    "(Optional) Discord token to use for this request",
                ),
            ),
        ) { ctx ->
            val prefs = settings
            val username = ctx.getString("username")?.trim()
            if (username.isNullOrEmpty()) {
                return@registerCommand CommandsAPI.CommandResult("Please provide a username.")
            }

            // Get token: prefer argument, then settings, then StoreStream
            var token = ctx.getString("token")?.trim()
            if (token.isNullOrEmpty()) {
                token = prefs.getString("addfriend_token", null)
            }
            if (token.isNullOrEmpty()) {
                try {
                    val storeAuth = Class.forName("com.discord.stores.StoreStream")
                        .getMethod("getAuthentication")
                        .invoke(null)
                    val field = storeAuth.javaClass.getDeclaredField("token")
                    field.isAccessible = true
                    token = field.get(storeAuth) as? String
                } catch (e: Exception) {
                    token = null
                }
            }
            if (token.isNullOrEmpty()) {
                return@registerCommand CommandsAPI.CommandResult("No token found. Use /set-addfriend-token or provide a token argument.")
            }

            // Use Aliucord's Http utility for the request
            val result = try {
                val response = com.aliucord.Http.Request("https://discord.com/api/v9/users/@me/relationships", "POST")
                    .setHeader("Authorization", token)
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody("{\"username\":\"$username\"}")

                val code = response.statusCode
                val body = response.text()

                if (code in 200..299) {
                    CommandsAPI.CommandResult("Friend request sent to $username!")
                } else {
                    // Parse body for captcha requirement
                    try {
                        val obj = org.json.JSONObject(body)
                        if (obj.has("captcha_sitekey")) {
                            val data = mutableMapOf<String, String>()
                            data["captcha_sitekey"] = obj.optString("captcha_sitekey")
                            data["captcha_session_id"] = obj.optString("captcha_session_id")
                            data["captcha_rqdata"] = obj.optString("captcha_rqdata")
                            data["captcha_rqtoken"] = obj.optString("captcha_rqtoken")
                            pendingCaptcha[username] = data
                            // Open in-app captcha UI immediately
                            try {
                                openCaptchaUI(context, username, data["captcha_sitekey"] ?: "")
                            } catch (_: Exception) {
                                // ignore
                            }
                            val endpoint = "https://discord.com/api/v9/users/@me/relationships"
                            return@registerCommand CommandsAPI.CommandResult("Request failed: $code: Bad Request ($endpoint)\n$body")
                        }
                    } catch (_: Exception) {
                    }
                    CommandsAPI.CommandResult("Error $code: $body")
                }
            } catch (e: Exception) {
                CommandsAPI.CommandResult("Request failed: ${e.message}")
            }

            return@registerCommand result
        }

        // Command to submit captcha token and complete pending friend request
        commands.registerCommand(
            "addfriend-captcha",
            "Complete a pending friend request by providing hCaptcha token",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "username",
                    "(Optional) Username for which captcha was requested",
                ),
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "captcha",
                    "hCaptcha token obtained after solving the captcha",
                ),
            ),
        ) { ctx ->
            val username = ctx.getString("username")?.trim()
            val captcha = ctx.getString("captcha")?.trim()
            if (captcha.isNullOrEmpty()) {
                return@registerCommand CommandsAPI.CommandResult("Please provide the captcha token.")
            }
            val key = username ?: pendingCaptcha.keys.firstOrNull()
            if (key == null || !pendingCaptcha.containsKey(key)) {
                return@registerCommand CommandsAPI.CommandResult("No pending captcha found. Provide the username or run /add-friend first to trigger captcha.")
            }
            val data = pendingCaptcha[key]!!

            // Reuse token extraction for auth
            var token = ctx.getString("token")?.trim()
            if (token.isNullOrEmpty()) token = settings.getString("addfriend_token", null)
            if (token.isNullOrEmpty()) {
                try {
                    val storeAuth = Class.forName("com.discord.stores.StoreStream")
                        .getMethod("getAuthentication")
                        .invoke(null)
                    val field = storeAuth.javaClass.getDeclaredField("token")
                    field.isAccessible = true
                    token = field.get(storeAuth) as? String
                } catch (e: Exception) {
                    token = null
                }
            }
            if (token.isNullOrEmpty()) {
                return@registerCommand CommandsAPI.CommandResult("No token found. Use /set-addfriend-token or provide a token argument.")
            }

            val payloadObj = org.json.JSONObject()
            payloadObj.put("username", key)
            payloadObj.put("captcha_key", captcha)
            if (data["captcha_rqtoken"]?.isNotEmpty() == true) payloadObj.put("captcha_rqtoken", data["captcha_rqtoken"])
            if (data["captcha_rqdata"]?.isNotEmpty() == true) payloadObj.put("captcha_rqdata", data["captcha_rqdata"])

            val result = try {
                val response = com.aliucord.Http.Request("https://discord.com/api/v9/users/@me/relationships", "POST")
                    .setHeader("Authorization", token)
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody(payloadObj.toString())

                val code = response.statusCode
                val body = response.text()
                if (code in 200..299) {
                    pendingCaptcha.remove(key)
                    CommandsAPI.CommandResult("Friend request completed for $key!")
                } else {
                    CommandsAPI.CommandResult("Error $code: $body")
                }
            } catch (e: Exception) {
                CommandsAPI.CommandResult("Request failed: ${e.message}")
            }

            return@registerCommand result
        }

        // Command to open an in-app hCaptcha UI and auto-submit the token
        commands.registerCommand(
            "addfriend-captcha-ui",
            "Open an in-app hCaptcha widget to solve and submit",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "username",
                    "(Optional) Username for which captcha was requested",
                ),
            ),
        ) { ctx ->
            val username = ctx.getString("username")?.trim()
            val key = username ?: pendingCaptcha.keys.firstOrNull()
            if (key == null || !pendingCaptcha.containsKey(key)) {
                return@registerCommand CommandsAPI.CommandResult("No pending captcha found. Trigger /add-friend first to get a captcha.")
            }
            val sitekey = pendingCaptcha[key]?.get("captcha_sitekey") ?: return@registerCommand CommandsAPI.CommandResult("No sitekey available for $key")

            // Prepare HTML for hCaptcha widget
            val html = """
                <html>
                  <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
                    <style>body{margin:0;padding:0;height:100vh}#hcaptcha{display:flex;justify-content:center;align-items:center;height:100vh}</style>
                  </head>
                  <body>
                    <div id="hcaptcha"></div>
                    <script>
                      function onSuccess(token){
                        Android.onToken(token);
                      }
                      function onLoad(){
                        hcaptcha.render('hcaptcha', {sitekey: '${sitekey}', callback: onSuccess});
                      }
                      window.onload = onLoad;
                    </script>
                  </body>
                </html>
            """.trimIndent()

            // Show WebView dialog on UI thread
            Handler(Looper.getMainLooper()).post {
                try {
                    val webView = WebView(context)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.webViewClient = WebViewClient()
                    webView.webChromeClient = WebChromeClient()

                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onToken(token: String) {
                            val res = submitPendingCaptchaInternal(context, key, token, null)
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    AlertDialog.Builder(context)
                                        .setTitle("hCaptcha Result")
                                        .setMessage(res)
                                        .setPositiveButton("OK", null)
                                        .show()
                                } catch (_: Exception) {}
                            }
                        }
                    }, "Android")

                    val dialog = AlertDialog.Builder(context)
                        .setTitle("Solve hCaptcha for $key")
                        .setView(webView)
                        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                        .setCancelable(true)
                        .create()

                    dialog.show()
                    webView.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null)
                } catch (e: Exception) {
                    // Fall back to instructing user to paste token
                }
            }

            CommandsAPI.CommandResult("Opened hCaptcha UI for $key. Solve it in the dialog to auto-submit.")
        }

        // Command to parse raw captcha JSON and open the in-app UI
        commands.registerCommand(
            "addfriend-captcha-raw",
            "Parse raw captcha JSON and open in-app hCaptcha UI",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "username",
                    "Username for which captcha was requested",
                ),
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "raw",
                    "Raw captcha JSON (paste the JSON from the error)",
                ),
            ),
        ) { ctx ->
            val username = ctx.getString("username")?.trim()
            val raw = ctx.getString("raw")?.trim()
            if (username.isNullOrEmpty()) return@registerCommand CommandsAPI.CommandResult("Please provide the username as well.")
            if (raw.isNullOrEmpty()) return@registerCommand CommandsAPI.CommandResult("Please provide the raw captcha JSON.")
            try {
                val obj = org.json.JSONObject(raw)
                if (!obj.has("captcha_sitekey")) return@registerCommand CommandsAPI.CommandResult("Provided JSON doesn't contain captcha_sitekey.")
                val data = mutableMapOf<String, String>()
                data["captcha_sitekey"] = obj.optString("captcha_sitekey")
                data["captcha_session_id"] = obj.optString("captcha_session_id")
                data["captcha_rqdata"] = obj.optString("captcha_rqdata")
                data["captcha_rqtoken"] = obj.optString("captcha_rqtoken")
                pendingCaptcha[username] = data
                try {
                    openCaptchaUI(context, username, data["captcha_sitekey"] ?: "")
                } catch (_: Exception) {}
                return@registerCommand CommandsAPI.CommandResult("Opened hCaptcha UI for $username.")
            } catch (e: Exception) {
                return@registerCommand CommandsAPI.CommandResult("Failed to parse JSON: ${e.message}")
            }
        }

        // A bit more advanced command with arguments
        commands.registerCommand(
            "hellowitharguments",
            "Hello World but with arguments!",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "name",
                    "Person to say hello to",
                ),
                Utils.createCommandOption(
                    ApplicationCommandType.USER,
                    "user",
                    "User to say hello to",
                ),
            ),
        ) { ctx ->
            // Check if a user argument was passed
            val username = if (ctx.containsArg("user")) {
                ctx.getRequiredUser("user").username
            } else {
                // Returns either the argument value if present, or the defaultValue ("World" in this case)
                ctx.getStringOrDefault("name", "World")
            }

            // Return the final result that will be displayed in chat as a response to the command
            CommandsAPI.CommandResult("Hello $username!")
        }

        // Patch that adds an embed with message statistics to each message
        // Patched method is WidgetChatListAdapterItemMessage.onConfigure(int type, ChatListEntry entry)
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure", // Method name
            // Refer to https://kotlinlang.org/docs/reflection.html#class-references
            // and https://docs.oracle.com/javase/tutorial/reflect/class/classNew.html
            Int::class.java, // int type
            ChatListEntry::class.java, // ChatListEntry entry
        ) { param ->
            // see https://api.xposed.info/reference/de/robv/android/xposed/XC_MethodHook.MethodHookParam.html
            // Obtain the second argument passed to the method, so the ChatListEntry
            // Because this is a Message item, it will always be a MessageEntry, so cast it to that
            val entry = param.args[1] as MessageEntry
            val message = entry.message

            // You need to be careful when messing with messages, because they may be loading
            // (user sent a message, and it is currently sending)
            if (message.isLoading) return@after

            // Now add an embed with the statistics

            // This method may be called multiple times per message, e.g. if it is edited,
            // so first remove existing embeds
            message.embeds.removeAll {
                // MessageEmbed.getTitle() is actually obfuscated, but Aliucord provides extensions for commonly used
                // obfuscated Discord classes, so just import the MessageEmbed.title extension and boom goodbye obfuscation!
                it.title == "Message Statistics"
            }

            // Creating embeds is a pain, so Aliucord provides a convenient builder
            MessageEmbedBuilder().run {
                setTitle("Message Statistics")
                addField("Length", "${message.content?.length ?: 0}", false)
                addField("ID", message.id.toString(), false)

                message.embeds.add(build())
            }
        }

        // Patch that renames Juby to JoobJoob
        patcher.before<CoreUser>("getUsername") { param ->
            // see https://api.xposed.info/reference/de/robv/android/xposed/XC_MethodHook.MethodHookParam.html
            // in before, after and instead patches, `this` refers to the instance of the class
            // the patched method is on, so the CoreUser instance here
            if (id == 925141667688878090) {
                // setResult() in before patches skips original method invocation
                param.result = "JoobJoob"
            }
        }

        // Patch that hides your typing status by replacing the method and simply doing nothing
        patcher.instead<StoreUserTyping>(
            "setUserTyping",
            Long::class.java, // java.lang.Long channelId
        ) {
            // Return null
            null
        }
        
        // helper: ensure our profile action is attempted again after patches
        try {
            registerProfileAction(context)
        } catch (_: Exception) {}
    }

    private fun registerProfileAction(context: Context) {
        val candidates = setOf(
            "com.discord.widgets.user.popout.WidgetUserProfile",
            "com.discord.widgets.user.profile.UserProfileActivity",
            "com.discord.widgets.user.popout.UserPopout",
            "com.discord.activities.UserProfileActivity",
            "com.discord.widgets.user.profile.UserProfileFragment"
        )

        try {
            val app = context.applicationContext as? android.app.Application ?: return
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                    try {
                        if (!candidates.contains(activity.javaClass.name)) return
                        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
                        // Avoid adding multiple times
                        val tag = "af_username_only_button"
                        if (root.findViewWithTag<android.view.View>(tag) != null) return

                        val ctx = activity
                        val btn = android.widget.Button(ctx)
                        btn.tag = tag
                        btn.text = "Add friend (username only)"
                        btn.setOnClickListener { showAddFriendDialog(ctx) }

                        // Add to root; best-effort placement
                        root.addView(btn)
                    } catch (_: Exception) {}
                }

                override fun onActivityStarted(activity: android.app.Activity) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivityStopped(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            })
        } catch (_: Exception) {}
    }

    private fun showAddFriendDialog(context: Context) {
        try {
            val input = android.widget.EditText(context)
            input.hint = "Username (no discriminator)"

            Handler(Looper.getMainLooper()).post {
                try {
                    AlertDialog.Builder(context)
                        .setTitle("Add friend (username only)")
                        .setView(input)
                        .setPositiveButton("Send") { _, _ ->
                            val username = input.text?.toString()?.trim()
                            if (!username.isNullOrEmpty()) {
                                // fire request and reuse captcha handling
                                sendUsernameFriendRequest(context, username)
                            } else {
                                try {
                                    AlertDialog.Builder(context)
                                        .setTitle("Error")
                                        .setMessage("Please provide a username.")
                                        .setPositiveButton("OK", null)
                                        .show()
                                } catch (_: Exception) {}
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .setCancelable(true)
                        .show()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun sendUsernameFriendRequest(context: Context, username: String) {
        Thread {
            // Reuse token extraction logic
            var token: String? = settings.getString("addfriend_token", null)
            if (token.isNullOrEmpty()) {
                try {
                    val storeAuth = Class.forName("com.discord.stores.StoreStream")
                        .getMethod("getAuthentication")
                        .invoke(null)
                    val field = storeAuth.javaClass.getDeclaredField("token")
                    field.isAccessible = true
                    token = field.get(storeAuth) as? String
                } catch (e: Exception) {
                    token = null
                }
            }
            if (token.isNullOrEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        AlertDialog.Builder(context)
                            .setTitle("No token")
                            .setMessage("No token found. Use /set-addfriend-token or supply one in settings.")
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (_: Exception) {}
                }
                return@Thread
            }

            try {
                val payload = org.json.JSONObject()
                payload.put("username", username)

                val response = com.aliucord.Http.Request("https://discord.com/api/v9/users/@me/relationships", "POST")
                    .setHeader("Authorization", token)
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody(payload.toString())

                val code = response.statusCode
                val body = response.text()
                if (code in 200..299) {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            AlertDialog.Builder(context)
                                .setTitle("Success")
                                .setMessage("Friend request sent to $username")
                                .setPositiveButton("OK", null)
                                .show()
                        } catch (_: Exception) {}
                    }
                } else {
                    try {
                        val obj = org.json.JSONObject(body)
                        if (obj.has("captcha_sitekey")) {
                            val data = mutableMapOf<String, String>()
                            data["captcha_sitekey"] = obj.optString("captcha_sitekey")
                            data["captcha_session_id"] = obj.optString("captcha_session_id")
                            data["captcha_rqdata"] = obj.optString("captcha_rqdata")
                            data["captcha_rqtoken"] = obj.optString("captcha_rqtoken")
                            pendingCaptcha[username] = data
                            try {
                                openCaptchaUI(context, username, data["captcha_sitekey"] ?: "")
                            } catch (_: Exception) {}
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    AlertDialog.Builder(context)
                                        .setTitle("Captcha Required")
                                        .setMessage("Captcha required for $username. Opened hCaptcha UI.")
                                        .setPositiveButton("OK", null)
                                        .show()
                                } catch (_: Exception) {}
                            }
                            return@Thread
                        }
                    } catch (_: Exception) {}

                    Handler(Looper.getMainLooper()).post {
                        try {
                            AlertDialog.Builder(context)
                                .setTitle("Error")
                                .setMessage("Error $code: $body")
                                .setPositiveButton("OK", null)
                                .show()
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        AlertDialog.Builder(context)
                            .setTitle("Request failed")
                            .setMessage(e.message ?: "Unknown error")
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    // Best-effort: attempt to patch common Add Friend UI validations so discriminator is not required
    private fun patchBuiltInAddFriendFlow() {
        // Best-effort patching is unreliable across Aliucord/Discord versions.
        // Keep this as a no-op placeholder to avoid compile-time issues.
    }
    }

    override fun stop(context: Context) {
        // Remove all patches
        patcher.unpatchAll()
    }
}
