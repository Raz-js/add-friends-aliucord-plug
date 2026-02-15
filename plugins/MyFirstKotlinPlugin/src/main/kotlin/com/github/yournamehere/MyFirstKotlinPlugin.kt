package com.github.yournamehere

import android.content.Context
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
    override fun start(context: Context) {
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
            ),
        ) { ctx ->
            val prefs = settings
            val username = ctx.getString("username")?.trim()
            if (username.isNullOrEmpty()) {
                return@registerCommand CommandsAPI.CommandResult("Please provide a username.")
            }

            // Get or prompt for token
            // Try to extract token automatically using reflection (StoreAuth.token)
            var token = prefs.getString("addfriend_token", null)
            if (token.isNullOrEmpty()) {
                try {
                    val storeAuthClass = Class.forName("com.discord.stores.StoreAuth")
                    val instanceField = storeAuthClass.getDeclaredField("INSTANCE")
                    instanceField.isAccessible = true
                    val storeAuthInstance = instanceField.get(null)
                    val tokenField = storeAuthClass.getDeclaredField("token")
                    tokenField.isAccessible = true
                    token = tokenField.get(storeAuthInstance) as? String
                } catch (e: Exception) {
                    token = null
                }
            }
            if (token.isNullOrEmpty()) {
                return@registerCommand CommandsAPI.CommandResult(
                    "Could not extract Discord token automatically. Please use /set-addfriend-token <token> to set it manually."
                )
            }

            // Stealth headers
            val headers = mapOf(
                "Authorization" to token,
                "User-Agent" to "Discord-Android/200000", // Real Discord Android UA
                "X-Discord-Locale" to "en-US",
                // X-Super-Properties is required for stealth, but must be copied from a real client
                // You can sniff this from your device or use a static value from a real session
                "X-Super-Properties" to "eyJv...snip...", // <-- Replace with a real value for best stealth
                "Content-Type" to "application/json"
            )

            fun httpRequest(url: String, payload: String?, method: String, headers: Map<String, String>): String {
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = method
                    for ((k, v) in headers) conn.setRequestProperty(k, v)
                    conn.doInput = true
                    if (payload != null) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(payload.toByteArray()) }
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    return stream.bufferedReader().readText()
                } catch (e: Exception) {
                    return "error: ${e.message}"
                }
            }

            // Send friend request using new Discord username system
            val friendUrl = "https://discord.com/api/v9/users/@me/relationships"
            val friendPayload = "{\"username\":\"$username\"}"
            val friendResp = httpRequest(friendUrl, friendPayload, "POST", headers)
            if (friendResp.contains("You are being rate limited") || friendResp.contains("error")) {
                return@registerCommand CommandsAPI.CommandResult("Failed to send friend request: $friendResp")
            }
            if (friendResp.contains("405") || friendResp.contains("method not allowed")) {
                return@registerCommand CommandsAPI.CommandResult("Friend request endpoint does not allow POST requests (405 Method Not Allowed).\nRaw: $friendResp")
            }
            CommandsAPI.CommandResult("Friend request sent to $username!")
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
    }

    override fun stop(context: Context) {
        // Remove all patches
        patcher.unpatchAll()
    }
}
