import bot.handleCommands
import bot.registerCommands
import bot.startScheduler
import data.DatabaseFactory
import dev.kord.core.Kord
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 TODO:
        1. Error handling for sign up
        2. Return additional match info in response
        3. Return "End Game Screen" Level details for match info
        4. Integrate with Deadlock GC
        5. Ephemeral responses for Commands in the future
 */

suspend fun main() = coroutineScope {
    DatabaseFactory.init()
    val kord = Kord(Env.DISCORD_TOKEN)
    registerCommands(kord)
    handleCommands(kord)
    launch {
        startScheduler(kord)
    }
    kord.login {
        presence { playing("tracking Deadlock matches") }
    }
}