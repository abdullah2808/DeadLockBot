import bot.handleCommands
import bot.registerCommands
import bot.startScheduler
import data.DatabaseFactory
import dev.kord.core.Kord
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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